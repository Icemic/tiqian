package org.tiqian.web

import kotlinx.browser.document
import org.w3c.dom.HTMLElement

internal val mounted = mutableListOf<HTMLElement>()

internal fun mount(html: String, sharedStylesReady: Boolean = true): HTMLElement {
    installDefaultPreparedDomFixture()
    val wrapper = document.createElement("div") as HTMLElement
    wrapper.innerHTML = html
    val root = wrapper.firstElementChild as HTMLElement
    if (sharedStylesReady) {
        root.style.setProperty("--tq-styles-ready", "1")
    }
    document.body!!.appendChild(root)
    mounted += root
    return root
}

internal fun testOptions(): TiqianWeb.EnhanceOptions =
    TiqianWeb.EnhanceOptions(
        fontSize = 18f,
        lineHeight = 30f,
    )

internal fun exactTestOptions(): TiqianWeb.EnhanceOptions = TiqianWeb.EnhanceOptions(
    paragraphSelector = "p[data-tq-snapshot-key]",
    exactFontSession = TiqianWeb.ExactFontSessionCapability(
        status = "conforming",
        sessionId = "fixture-exact-session",
        detail = "test",
    ),
)

internal val enginePunctuationFeatureStyle: String
    get() = """
        <style>
          [data-tq-rendered="true"] {
            font-feature-settings: "halt" 0, "chws" 0, "palt" 0 !important;
          }
          [data-tq-rendered="true"] span[data-tq-open-type-features="pwid,palt"] {
            font-feature-settings: "halt" 0, "chws" 0, "palt" 1 !important;
          }
        </style>
    """.trimIndent()
