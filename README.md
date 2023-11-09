# How to "hide" dependencies containing macro includes?

In Gradle's native plugins, macro includes result in all header search paths being inputs for the compilation unit, potentially causing excessive recompilation.
This default behavior is evidenced in the info log: `Cannot locate header file for '#include MACRO' in source file 'file.hpp'. Assuming changed.`
This leads Gradle to consider every file in the include path as a dependency to prevent flaky builds.

Gradle has an internal flag, activated via a system property (`org.gradle.internal.native.headers.unresolved.dependencies.ignore=true`), which allows it to disregard unresolved header dependencies.
While this could result in "missing" headers, in most real-life scenarios, such "missing" headers are unlikely to affect the compilation process.

For stable and infrequently updated dependencies, leveraging separate dependency variants for headers and version markers allows Gradle to snapshot the header search paths, passed by arguments, by value.
This effectively shields the search paths from Gradle's header dependency discovery, focusing on the explicit paths provided.

A/B testing should be conducted to confirm that this method indeed accelerates the build process.
While custom features can offer tailored solutions, it's generally preferable to utilize core Gradle features for build optimization, ensuring a balance between performance gains and maintainability.