import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.attributes.Attribute;
import org.gradle.api.attributes.AttributeContainer;
import org.gradle.api.attributes.Usage;
import org.gradle.api.file.FileSystemLocation;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.language.cpp.CppBinary;
import org.gradle.language.cpp.tasks.CppCompile;
import org.gradle.nativeplatform.toolchain.GccCompatibleToolChain;
import org.gradle.nativeplatform.toolchain.NativeToolChain;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class SystemIncludesPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getComponents().withType(CppBinary.class).configureEach(binary -> {
            // Collect the System headers/versions from upstream projects
            final Configuration systemHeaders = createSystemHeaders(project, binary);
            final Configuration systemVersions = createSystemVersions(project, binary);

            // Configure the compile task with the specified System headers/versions
            //   Note that we compute the task name to avoid realizing the task unnecessarily.
            project.getTasks().named(compileTaskName(binary), CppCompile.class, task -> {
                // We don't care about the path of the file but only about the content of the file.
                // Note: The content of the version file matching the dependency has to change if the system headers change in any way.
                task.getInputs().files(systemVersions).withPathSensitivity(PathSensitivity.NONE);

                // One small issue about these include flags:
                //   Because Gradle captures compilerArgs by value,
                //   the up-to-dateness of the task will be dependent on the location of the System headers.
                //   To work around this issue, one strategy can be to relativize the path.
                //   In this case it works because the system headers are installed locally in the project.
                //   We could also use symbolic links to the target, if the files are resolved remotely by Gradle.
                //   When Gradle resolves the files remotely, the include path will end up under the user home.
                //   Alternatively, "vendor projects" could be created to sync the System headers under the multi-project and all paths are relativize to those projects (similar to what we do in this example).
                task.getCompilerArgs().addAll(task.getToolChain().zip(systemHeaders.getElements(), toIncludeFlags(relativizeAgainst(task.getObjectFileDir().getLocationOnly().map(it -> it.getAsFile().toPath())))));
            });

            // Propagate the System headers/versions to downstream projects
            createSystemHeadersElements(project, binary);
            createSystemVersionsElements(project, binary);
        });
    }

    //region Include flags
    private static UnaryOperator<Path> relativizeAgainst(Provider<Path> projectDir) {
        return it -> projectDir.get().relativize(it);
    }

    private static BiFunction<NativeToolChain, Set<FileSystemLocation>, List<String>> toIncludeFlags(UnaryOperator<Path> transform) {
        return (toolChain, searchPaths) -> {
            if (toolChain instanceof GccCompatibleToolChain) {
                return searchPaths.stream().flatMap(it -> Stream.of("-I", transform.apply(it.getAsFile().toPath()).toString())).collect(Collectors.toList());
            } else {
                throw new UnsupportedOperationException("unsupported toolchain: " + toolChain);
            }
        };
    }
    //endregion

    //region Configurations
    private static Configuration createSystemHeaders(Project project, CppBinary binary) {
        final Configuration cppCompile = project.getConfigurations().getByName("cppCompile" + capitalize(variantName(binary)));
        final Configuration implementation = project.getConfigurations().getByName("main" + capitalize(variantName(binary)) + "Implementation");

        return project.getConfigurations().create(variantName(binary) + "SystemHeaders", configuration -> {
            configuration.setDescription(String.format("System headers dependencies for binary '%s'.", binary.getName()));
            configuration.setCanBeResolved(true);
            configuration.setCanBeConsumed(false);
            configuration.attributes(forUsage(project, "cplusplus-system-api"));
            configuration.attributes(copyAttributes(attributesOf(cppCompile).filter(notUsageAttribute())));
            configuration.extendsFrom(implementation);
        });
    }

    private static Configuration createSystemHeadersElements(Project project, CppBinary binary) {
        final Configuration cppCompile = project.getConfigurations().getByName("cppCompile" + capitalize(variantName(binary)));
        final Configuration implementation = project.getConfigurations().getByName("main" + capitalize(variantName(binary)) + "Implementation");

        return project.getConfigurations().create(variantName(binary) + "SystemHeadersElements", configuration -> {
            configuration.setDescription(String.format("System headers elements dependencies for binary '%s'.", binary.getName()));
            configuration.setCanBeResolved(false);
            configuration.setCanBeConsumed(true);
            configuration.attributes(forUsage(project, "cplusplus-system-api"));
            configuration.attributes(copyAttributes(attributesOf(cppCompile).filter(notUsageAttribute())));
            configuration.extendsFrom(implementation);
        });
    }

    private static Configuration createSystemVersions(Project project, CppBinary binary) {
        final Configuration cppCompile = project.getConfigurations().getByName("cppCompile" + capitalize(variantName(binary)));
        final Configuration implementation = project.getConfigurations().getByName("main" + capitalize(variantName(binary)) + "Implementation");

        return project.getConfigurations().create(variantName(binary) + "SystemVersions", configuration -> {
            configuration.setDescription(String.format("System versions dependencies for binary '%s'.", binary.getName()));
            configuration.setCanBeResolved(true);
            configuration.setCanBeConsumed(false);
            configuration.attributes(forUsage(project, "cplusplus-system-api-version"));
            configuration.attributes(copyAttributes(attributesOf(cppCompile).filter(notUsageAttribute())));
            configuration.extendsFrom(implementation);
        });
    }

    private static Configuration createSystemVersionsElements(Project project, CppBinary binary) {
        final Configuration cppCompile = project.getConfigurations().getByName("cppCompile" + capitalize(variantName(binary)));
        final Configuration implementation = project.getConfigurations().getByName("main" + capitalize(variantName(binary)) + "Implementation");

        return project.getConfigurations().create(variantName(binary) + "SystemVersionsElements", configuration -> {
            configuration.setDescription(String.format("System versions elements dependencies for binary '%s'.", binary.getName()));
            configuration.setCanBeResolved(false);
            configuration.setCanBeConsumed(true);
            configuration.attributes(forUsage(project,"cplusplus-system-api-version"));
            configuration.attributes(copyAttributes(attributesOf(cppCompile).filter(notUsageAttribute())));
            configuration.extendsFrom(implementation);
        });
    }
    //endregion

    //region Configuration attributes
    private static Action<AttributeContainer> forUsage(Project project, String value) {
        return it -> it.attribute(Usage.USAGE_ATTRIBUTE, project.getObjects().named(Usage.class, value));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Action<AttributeContainer> copyAttributes(Stream<Map.Entry<Attribute, Object>> attributes) {
        return it -> attributes.forEach(e -> it.attribute(e.getKey(), e.getValue()));
    }

    @SuppressWarnings("rawtypes")
    private static Predicate<Map.Entry<Attribute, Object>> notUsageAttribute() {
        return entry -> !entry.getKey().getType().equals(Usage.class);
    }

    @SuppressWarnings("rawtypes")
    private static Stream<Map.Entry<Attribute, Object>> attributesOf(Configuration configuration) {
        return configuration.getAttributes().keySet().stream()
                .map(key -> new AbstractMap.SimpleImmutableEntry<>(key, configuration.getAttributes().getAttribute(key)));
    }
    //endregion

    //region Names
    private static String variantName(CppBinary binary) {
        return uncapitalize(binary.getName().substring("main".length()));
    }

    private static String compileTaskName(CppBinary binary) {
        return "compile" + capitalize(variantName(binary)) + "Cpp";
    }
    //endregion

    //region StringUtils
    private static String uncapitalize(String s) {
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static String capitalize(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
    //endregion
}
