# Draft: Central publishing limit request for org.tiqian

- To: central-support@sonatype.com
- Subject: Limit increase request for org.tiqian

---

Hello,

We maintain the `org.tiqian` namespace: an open-source (MPL-2.0) Kotlin Multiplatform
CJK text layout suite, published from three repositories under one organization
(https://github.com/tiqian-cjk — tiqian, tiqian-math, tiqian-markdown).

The Usage Center flagged us over the limits in August. The cause is structural rather
than high volume: each release is a lockstep release of the three repositories, so it
counts as three deployments, and each Kotlin Multiplatform module publishes per-target
artifacts along with metadata, sources, and javadoc.

We have already cut our footprint: a suite release went from ~103 publications to ~35
(about 1,500 files as counted by the Usage Center, ~20 MB), and day-to-day dev builds
now go to the SNAPSHOT channel instead.

Two limits still don't fit the suite's shape:

- File count: one suite release is about 1,500 files against the 1,000/month threshold.
- Release count: one suite release is three deployments, so 7/month allows only two.

We'd like to request around 5,000 files/month and 15 releases/month for `org.tiqian`,
or whatever tier you consider appropriate. Release size stays within the free limit.

Thanks,
123Duo3
Maintainer, Tiqian (org.tiqian)
https://github.com/tiqian-cjk
