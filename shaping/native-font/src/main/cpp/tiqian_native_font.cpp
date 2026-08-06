#include <jni.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <memory>
#include <mutex>
#include <string>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <vector>

#include <hb-ft.h>
#include <hb-ot.h>
#include <hb.h>

#include <ft2build.h>
#include FT_FREETYPE_H
#include FT_MULTIPLE_MASTERS_H
#include FT_OUTLINE_H
#include FT_TRUETYPE_TABLES_H

namespace {

constexpr int kLoadFlags = FT_LOAD_NO_BITMAP | FT_LOAD_NO_HINTING;

FT_Library g_freetype = nullptr;
std::once_flag g_freetype_once;
std::atomic<jlong> g_live_source_count{0};
std::atomic<jlong> g_live_source_bytes{0};
std::atomic<jlong> g_live_face_count{0};

void throw_java(JNIEnv* env, const char* class_name, const std::string& message) {
    jclass cls = env->FindClass(class_name);
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

bool ensure_freetype(JNIEnv* env) {
    std::call_once(g_freetype_once, [] {
        if (FT_Init_FreeType(&g_freetype) != 0) g_freetype = nullptr;
    });
    if (g_freetype != nullptr) return true;
    throw_java(env, "java/lang/IllegalStateException", "FreeType initialization failed");
    return false;
}

struct FontBlob {
    const unsigned char* data = nullptr;
    size_t size = 0;
    void* mapping = MAP_FAILED;
    JavaVM* java_vm = nullptr;
    jobject direct_buffer = nullptr;

    ~FontBlob() {
        if (mapping != MAP_FAILED) munmap(mapping, size);
        if (direct_buffer != nullptr && java_vm != nullptr) {
            JNIEnv* env = nullptr;
            bool attached = false;
            const jint status = java_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
            if (status == JNI_EDETACHED && java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            }
            if (env != nullptr) env->DeleteGlobalRef(direct_buffer);
            if (attached) java_vm->DetachCurrentThread();
        }
        g_live_source_bytes.fetch_sub(static_cast<jlong>(size), std::memory_order_relaxed);
        g_live_source_count.fetch_sub(1, std::memory_order_relaxed);
    }
};

using FontBlobHandle = std::shared_ptr<FontBlob>;

struct Face {
    FontBlobHandle source;
    FT_Face ft_face = nullptr;
    hb_font_t* hb_font = nullptr;
    bool counted = false;
    std::mutex mutex;

    ~Face() {
        if (hb_font != nullptr) hb_font_destroy(hb_font);
        if (ft_face != nullptr) FT_Done_Face(ft_face);
        if (counted) g_live_face_count.fetch_sub(1, std::memory_order_relaxed);
    }
};

struct Shape {
    std::vector<int> ids;
    std::vector<int> clusters;
    // x, y, xAdvance, yAdvance per glyph; Android/core coordinates (+y down).
    std::vector<float> positions;
    // left, top, right, bottom per glyph; NaN quartet means unavailable.
    std::vector<float> bounds;
    float advance = 0.0f;
    int missing_glyphs = 0;
};

Face* face_from(jlong handle) {
    return reinterpret_cast<Face*>(static_cast<intptr_t>(handle));
}

FontBlobHandle* source_from(jlong handle) {
    return reinterpret_cast<FontBlobHandle*>(static_cast<intptr_t>(handle));
}

Shape* shape_from(jlong handle) {
    return reinterpret_cast<Shape*>(static_cast<intptr_t>(handle));
}

bool configure_face(Face* face, float font_size) {
    const auto size_26_6 = static_cast<FT_F26Dot6>(std::llround(font_size * 64.0f));
    if (FT_Set_Char_Size(face->ft_face, 0, size_26_6, 72, 72) != 0) return false;
    const int scale_26_6 = static_cast<int>(std::llround(font_size * 64.0f));
    hb_font_set_scale(face->hb_font, scale_26_6, scale_26_6);
    hb_ft_font_set_load_flags(face->hb_font, kLoadFlags);
    hb_ft_font_changed(face->hb_font);
    return true;
}

std::string to_utf8(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::vector<hb_feature_t> parse_features(const std::string& csv) {
    std::vector<hb_feature_t> result;
    size_t start = 0;
    while (start < csv.size()) {
        const size_t end = csv.find(',', start);
        const std::string item = csv.substr(start, end == std::string::npos ? std::string::npos : end - start);
        hb_feature_t feature{};
        if (!item.empty() && hb_feature_from_string(item.c_str(), static_cast<int>(item.size()), &feature)) {
            result.push_back(feature);
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return result;
}

int next_codepoint(const jchar* chars, int length, int* index) {
    const int high = chars[(*index)++];
    if (high < 0xD800 || high > 0xDBFF || *index >= length) return high;
    const int low = chars[*index];
    if (low < 0xDC00 || low > 0xDFFF) return high;
    ++(*index);
    return 0x10000 + ((high - 0xD800) << 10) + (low - 0xDC00);
}

struct OutlineBuilder {
    std::vector<float> values;
    bool contour_open = false;
};

int outline_move_to(const FT_Vector* to, void* user) {
    auto* builder = static_cast<OutlineBuilder*>(user);
    if (builder->contour_open) builder->values.push_back(4.0f);  // close
    builder->values.push_back(0.0f);
    builder->values.push_back(static_cast<float>(to->x));
    builder->values.push_back(static_cast<float>(to->y));
    builder->contour_open = true;
    return 0;
}

int outline_line_to(const FT_Vector* to, void* user) {
    auto* builder = static_cast<OutlineBuilder*>(user);
    builder->values.push_back(1.0f);
    builder->values.push_back(static_cast<float>(to->x));
    builder->values.push_back(static_cast<float>(to->y));
    return 0;
}

int outline_conic_to(const FT_Vector* control, const FT_Vector* to, void* user) {
    auto* builder = static_cast<OutlineBuilder*>(user);
    builder->values.push_back(2.0f);
    builder->values.push_back(static_cast<float>(control->x));
    builder->values.push_back(static_cast<float>(control->y));
    builder->values.push_back(static_cast<float>(to->x));
    builder->values.push_back(static_cast<float>(to->y));
    return 0;
}

int outline_cubic_to(
    const FT_Vector* control1,
    const FT_Vector* control2,
    const FT_Vector* to,
    void* user
) {
    auto* builder = static_cast<OutlineBuilder*>(user);
    builder->values.push_back(3.0f);
    builder->values.push_back(static_cast<float>(control1->x));
    builder->values.push_back(static_cast<float>(control1->y));
    builder->values.push_back(static_cast<float>(control2->x));
    builder->values.push_back(static_cast<float>(control2->y));
    builder->values.push_back(static_cast<float>(to->x));
    builder->values.push_back(static_cast<float>(to->y));
    return 0;
}

jintArray int_array(JNIEnv* env, const std::vector<int>& values) {
    jintArray result = env->NewIntArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetIntArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

jfloatArray float_array(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray result = env->NewFloatArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetFloatArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

jlongArray long_array(JNIEnv* env, const std::vector<jlong>& values) {
    jlongArray result = env->NewLongArray(static_cast<jsize>(values.size()));
    if (result != nullptr && !values.empty()) {
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(values.size()), values.data());
    }
    return result;
}

bool apply_variations(
    JNIEnv* env,
    FT_Face face,
    jintArray variation_tags,
    jfloatArray variation_values
) {
    const jsize tag_count = variation_tags == nullptr ? 0 : env->GetArrayLength(variation_tags);
    const jsize value_count = variation_values == nullptr ? 0 : env->GetArrayLength(variation_values);
    if (tag_count != value_count) {
        throw_java(env, "java/lang/IllegalArgumentException", "Variation tags and values must have equal lengths");
        return false;
    }
    if (tag_count == 0) return true;

    FT_MM_Var* mm_var = nullptr;
    if (FT_Get_MM_Var(face, &mm_var) != 0 || mm_var == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Variation coordinates require a variable font face");
        return false;
    }

    std::vector<jint> tags(static_cast<size_t>(tag_count));
    std::vector<jfloat> values(static_cast<size_t>(value_count));
    env->GetIntArrayRegion(variation_tags, 0, tag_count, tags.data());
    env->GetFloatArrayRegion(variation_values, 0, value_count, values.data());
    if (env->ExceptionCheck()) {
        FT_Done_MM_Var(g_freetype, mm_var);
        return false;
    }

    std::vector<FT_Fixed> coordinates(mm_var->num_axis);
    for (FT_UInt axis_index = 0; axis_index < mm_var->num_axis; ++axis_index) {
        coordinates[axis_index] = mm_var->axis[axis_index].def;
    }
    for (jsize requested_index = 0; requested_index < tag_count; ++requested_index) {
        const auto requested_tag = static_cast<FT_ULong>(static_cast<uint32_t>(tags[requested_index]));
        bool found = false;
        for (FT_UInt axis_index = 0; axis_index < mm_var->num_axis; ++axis_index) {
            const FT_Var_Axis& axis = mm_var->axis[axis_index];
            if (axis.tag != requested_tag) continue;
            const double scaled = static_cast<double>(values[requested_index]) * 65536.0;
            if (!std::isfinite(scaled) || scaled < axis.minimum || scaled > axis.maximum) {
                FT_Done_MM_Var(g_freetype, mm_var);
                throw_java(env, "java/lang/IllegalArgumentException", "Variation coordinate is outside the font axis range");
                return false;
            }
            coordinates[axis_index] = static_cast<FT_Fixed>(std::llround(scaled));
            found = true;
            break;
        }
        if (!found) {
            FT_Done_MM_Var(g_freetype, mm_var);
            throw_java(env, "java/lang/IllegalArgumentException", "Font does not declare the requested variation axis");
            return false;
        }
    }
    const FT_Error error = FT_Set_Var_Design_Coordinates(
        face,
        mm_var->num_axis,
        coordinates.data()
    );
    FT_Done_MM_Var(g_freetype, mm_var);
    if (error != 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "FreeType rejected the variation coordinates");
        return false;
    }
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeRegisterFileSource(
    JNIEnv* env,
    jobject,
    jstring path
) {
    if (path == nullptr) {
        throw_java(env, "java/lang/IllegalArgumentException", "Font path must not be null");
        return 0;
    }
    const std::string native_path = to_utf8(env, path);
    if (env->ExceptionCheck()) return 0;
    const int fd = open(native_path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        throw_java(env, "java/io/IOException", "Could not open font file: " + std::string(std::strerror(errno)));
        return 0;
    }
    struct stat status{};
    if (fstat(fd, &status) != 0) {
        const int saved_errno = errno;
        close(fd);
        throw_java(env, "java/io/IOException", "Could not stat font file: " + std::string(std::strerror(saved_errno)));
        return 0;
    }
    if (status.st_size <= 0 ||
        static_cast<uint64_t>(status.st_size) > static_cast<uint64_t>(std::numeric_limits<FT_Long>::max())) {
        close(fd);
        throw_java(env, "java/io/IOException", "Font file has an invalid size");
        return 0;
    }
    void* mapping = mmap(nullptr, static_cast<size_t>(status.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    const int saved_errno = errno;
    close(fd);
    if (mapping == MAP_FAILED) {
        throw_java(env, "java/io/IOException", "Could not map font file: " + std::string(std::strerror(saved_errno)));
        return 0;
    }
    auto source = std::make_shared<FontBlob>();
    source->mapping = mapping;
    source->data = static_cast<const unsigned char*>(mapping);
    source->size = static_cast<size_t>(status.st_size);
    g_live_source_count.fetch_add(1, std::memory_order_relaxed);
    g_live_source_bytes.fetch_add(static_cast<jlong>(source->size), std::memory_order_relaxed);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new FontBlobHandle(std::move(source))));
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeRegisterBufferSource(
    JNIEnv* env,
    jobject,
    jobject buffer,
    jlong size
) {
    if (buffer == nullptr || size <= 0 || size > std::numeric_limits<FT_Long>::max()) {
        throw_java(env, "java/lang/IllegalArgumentException", "Direct font buffer must not be empty");
        return 0;
    }
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity < size) {
        throw_java(env, "java/lang/IllegalArgumentException", "Font buffer must be direct and large enough");
        return 0;
    }
    JavaVM* java_vm = nullptr;
    if (env->GetJavaVM(&java_vm) != JNI_OK) {
        throw_java(env, "java/lang/IllegalStateException", "Could not retain the font buffer VM");
        return 0;
    }
    jobject retained_buffer = env->NewGlobalRef(buffer);
    if (retained_buffer == nullptr) return 0;
    auto source = std::make_shared<FontBlob>();
    source->data = static_cast<const unsigned char*>(address);
    source->size = static_cast<size_t>(size);
    source->java_vm = java_vm;
    source->direct_buffer = retained_buffer;
    g_live_source_count.fetch_add(1, std::memory_order_relaxed);
    g_live_source_bytes.fetch_add(size, std::memory_order_relaxed);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(new FontBlobHandle(std::move(source))));
}

extern "C" JNIEXPORT void JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeReleaseSource(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete source_from(handle);
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeCreateFace(
    JNIEnv* env,
    jobject,
    jlong source_handle,
    jint collection_index,
    jintArray variation_tags,
    jfloatArray variation_values
) {
    if (!ensure_freetype(env)) return 0;
    FontBlobHandle* source = source_from(source_handle);
    if (source == nullptr || !*source || (*source)->data == nullptr || (*source)->size == 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "Font source is closed or empty");
        return 0;
    }

    auto face = std::make_unique<Face>();
    face->source = *source;

    const FT_Error error = FT_New_Memory_Face(
        g_freetype,
        face->source->data,
        static_cast<FT_Long>(face->source->size),
        collection_index,
        &face->ft_face
    );
    if (error != 0) {
        throw_java(env, "java/lang/IllegalArgumentException", "FreeType rejected the font bytes or collection index");
        return 0;
    }
    if (!apply_variations(env, face->ft_face, variation_tags, variation_values)) return 0;
    face->hb_font = hb_ft_font_create_referenced(face->ft_face);
    if (face->hb_font == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "HarfBuzz could not create an FT-backed font");
        return 0;
    }
    hb_ft_font_set_load_flags(face->hb_font, kLoadFlags);
    g_live_face_count.fetch_add(1, std::memory_order_relaxed);
    face->counted = true;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(face.release()));
}

extern "C" JNIEXPORT void JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeReleaseFace(
    JNIEnv*,
    jobject,
    jlong handle
) {
    delete face_from(handle);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeResourceStats(
    JNIEnv* env,
    jobject
) {
    return long_array(env, {
        g_live_source_count.load(std::memory_order_relaxed),
        g_live_source_bytes.load(std::memory_order_relaxed),
        g_live_face_count.load(std::memory_order_relaxed),
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeUnitsPerEm(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    Face* face = face_from(handle);
    if (face == nullptr || face->ft_face == nullptr) {
        throw_java(env, "java/lang/IllegalStateException", "Font face is closed");
        return 0;
    }
    return static_cast<jint>(face->ft_face->units_per_EM);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeHasGlyphs(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text
) {
    Face* face = face_from(handle);
    if (face == nullptr || text == nullptr) return JNI_FALSE;
    const jsize length = env->GetStringLength(text);
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) return JNI_FALSE;
    bool covered = true;
    std::lock_guard<std::mutex> guard(face->mutex);
    for (int index = 0; index < length;) {
        const int codepoint = next_codepoint(chars, length, &index);
        // Variation selectors and join controls do not require nominal glyphs.
        if ((codepoint >= 0xFE00 && codepoint <= 0xFE0F) || codepoint == 0x200C || codepoint == 0x200D) continue;
        if (FT_Get_Char_Index(face->ft_face, static_cast<FT_ULong>(codepoint)) == 0) {
            covered = false;
            break;
        }
    }
    env->ReleaseStringChars(text, chars);
    return covered ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShape(
    JNIEnv* env,
    jobject,
    jlong handle,
    jstring text,
    jfloat font_size,
    jstring locale,
    jint script_code,
    jstring features_csv
) {
    Face* face = face_from(handle);
    if (face == nullptr || text == nullptr || !(font_size > 0.0f)) {
        throw_java(env, "java/lang/IllegalArgumentException", "Invalid face, text, or font size");
        return 0;
    }

    const jsize length = env->GetStringLength(text);
    const jchar* chars = env->GetStringChars(text, nullptr);
    if (chars == nullptr) return 0;

    auto result = std::make_unique<Shape>();
    {
        std::lock_guard<std::mutex> guard(face->mutex);
        if (!configure_face(face, font_size)) {
            env->ReleaseStringChars(text, chars);
            throw_java(env, "java/lang/IllegalStateException", "FreeType could not configure the requested font size");
            return 0;
        }

        hb_buffer_t* buffer = hb_buffer_create();
        hb_buffer_set_direction(buffer, HB_DIRECTION_LTR);
        if (script_code == 1) {
            hb_buffer_set_script(buffer, HB_SCRIPT_HAN);
        } else if (script_code == 2) {
            hb_buffer_set_script(buffer, HB_SCRIPT_LATIN);
        }
        const std::string language = to_utf8(env, locale);
        if (!language.empty()) hb_buffer_set_language(buffer, hb_language_from_string(language.c_str(), -1));
        hb_buffer_set_cluster_level(buffer, HB_BUFFER_CLUSTER_LEVEL_MONOTONE_CHARACTERS);
        hb_buffer_add_utf16(buffer, reinterpret_cast<const uint16_t*>(chars), length, 0, length);
        if (script_code == 0) hb_buffer_guess_segment_properties(buffer);

        const std::vector<hb_feature_t> features = parse_features(to_utf8(env, features_csv));
        hb_shape(face->hb_font, buffer, features.empty() ? nullptr : features.data(), static_cast<unsigned>(features.size()));

        unsigned count = 0;
        const hb_glyph_info_t* infos = hb_buffer_get_glyph_infos(buffer, &count);
        const hb_glyph_position_t* positions = hb_buffer_get_glyph_positions(buffer, &count);
        result->ids.reserve(count);
        result->clusters.reserve(count);
        result->positions.reserve(count * 4);
        result->bounds.reserve(count * 4);

        int pen_x = 0;
        int pen_y = 0;
        const float nan = std::numeric_limits<float>::quiet_NaN();
        for (unsigned index = 0; index < count; ++index) {
            const hb_codepoint_t glyph_id = infos[index].codepoint;
            result->ids.push_back(static_cast<int>(glyph_id));
            result->clusters.push_back(static_cast<int>(infos[index].cluster));
            result->positions.push_back((pen_x + positions[index].x_offset) / 64.0f);
            result->positions.push_back(-(pen_y + positions[index].y_offset) / 64.0f);
            result->positions.push_back(positions[index].x_advance / 64.0f);
            result->positions.push_back(-positions[index].y_advance / 64.0f);

            hb_glyph_extents_t extents{};
            if (hb_font_get_glyph_extents(face->hb_font, glyph_id, &extents)) {
                result->bounds.push_back(extents.x_bearing / 64.0f);
                result->bounds.push_back(-extents.y_bearing / 64.0f);
                result->bounds.push_back((extents.x_bearing + extents.width) / 64.0f);
                result->bounds.push_back(-(extents.y_bearing + extents.height) / 64.0f);
            } else {
                result->bounds.insert(result->bounds.end(), {nan, nan, nan, nan});
            }
            if (glyph_id == 0) ++result->missing_glyphs;
            pen_x += positions[index].x_advance;
            pen_y += positions[index].y_advance;
        }
        result->advance = pen_x / 64.0f;
        hb_buffer_destroy(buffer);
    }
    env->ReleaseStringChars(text, chars);
    return static_cast<jlong>(reinterpret_cast<intptr_t>(result.release()));
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShapeGlyphIds(
    JNIEnv* env, jobject, jlong handle
) {
    Shape* shape = shape_from(handle);
    return int_array(env, shape == nullptr ? std::vector<int>() : shape->ids);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShapeClusters(
    JNIEnv* env, jobject, jlong handle
) {
    Shape* shape = shape_from(handle);
    return int_array(env, shape == nullptr ? std::vector<int>() : shape->clusters);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShapePositions(
    JNIEnv* env, jobject, jlong handle
) {
    Shape* shape = shape_from(handle);
    return float_array(env, shape == nullptr ? std::vector<float>() : shape->positions);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShapeBounds(
    JNIEnv* env, jobject, jlong handle
) {
    Shape* shape = shape_from(handle);
    return float_array(env, shape == nullptr ? std::vector<float>() : shape->bounds);
}

extern "C" JNIEXPORT jfloat JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShapeAdvance(
    JNIEnv*, jobject, jlong handle
) {
    Shape* shape = shape_from(handle);
    return shape == nullptr ? 0.0f : shape->advance;
}

extern "C" JNIEXPORT jint JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeShapeMissingGlyphs(
    JNIEnv*, jobject, jlong handle
) {
    Shape* shape = shape_from(handle);
    return shape == nullptr ? 0 : shape->missing_glyphs;
}

extern "C" JNIEXPORT void JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeReleaseShape(
    JNIEnv*, jobject, jlong handle
) {
    delete shape_from(handle);
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeMetrics(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloat font_size
) {
    Face* face = face_from(handle);
    if (face == nullptr || face->ft_face == nullptr || face->ft_face->units_per_EM == 0) return nullptr;
    std::lock_guard<std::mutex> guard(face->mutex);
    const float scale = font_size / static_cast<float>(face->ft_face->units_per_EM);
    const TT_OS2* os2 = static_cast<const TT_OS2*>(FT_Get_Sfnt_Table(face->ft_face, ft_sfnt_os2));
    const float ascent = face->ft_face->ascender * scale;
    const float descent = -face->ft_face->descender * scale;
    const float height = face->ft_face->height * scale;
    const float nan = std::numeric_limits<float>::quiet_NaN();
    const float typo_ascent = os2 == nullptr ? nan : os2->sTypoAscender * scale;
    const float typo_descent = os2 == nullptr ? nan : -os2->sTypoDescender * scale;
    return float_array(env, {ascent, descent, std::max(0.0f, height - ascent - descent), typo_ascent, typo_descent});
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeOutline(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint glyph_id
) {
    Face* face = face_from(handle);
    if (face == nullptr || face->ft_face == nullptr) return nullptr;
    std::lock_guard<std::mutex> guard(face->mutex);
    const FT_Error error = FT_Load_Glyph(
        face->ft_face,
        static_cast<FT_UInt>(glyph_id),
        FT_LOAD_NO_BITMAP | FT_LOAD_NO_HINTING | FT_LOAD_NO_SCALE
    );
    if (error != 0 || face->ft_face->glyph->format != FT_GLYPH_FORMAT_OUTLINE) {
        // A valid outline with no contours (for example a space glyph) is
        // represented by an empty array below. nullptr instead means that the
        // registered face cannot replay this glyph as an outline, so Kotlin
        // must report the capability failure instead of drawing another font.
        return nullptr;
    }
    OutlineBuilder builder;
    FT_Outline_Funcs funcs{};
    funcs.move_to = outline_move_to;
    funcs.line_to = outline_line_to;
    funcs.conic_to = outline_conic_to;
    funcs.cubic_to = outline_cubic_to;
    funcs.shift = 0;
    funcs.delta = 0;
    if (FT_Outline_Decompose(&face->ft_face->glyph->outline, &funcs, &builder) != 0) {
        return nullptr;
    }
    if (builder.contour_open) builder.values.push_back(4.0f);
    return float_array(env, builder.values);
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_tiqian_shaping_nativefont_NativeFontBridge_nativeVersions(
    JNIEnv* env,
    jobject
) {
    if (!ensure_freetype(env)) return nullptr;
    int major = 0;
    int minor = 0;
    int patch = 0;
    FT_Library_Version(g_freetype, &major, &minor, &patch);
    const std::string value = std::string("harfbuzz=") + hb_version_string() +
        ";freetype=" + std::to_string(major) + "." + std::to_string(minor) + "." + std::to_string(patch);
    return env->NewStringUTF(value.c_str());
}
