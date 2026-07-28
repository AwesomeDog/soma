package io.github.awesomedog.soma;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.awesomedog.soma.exec.CommandRunner;
import io.github.awesomedog.soma.exec.Invocation;
import io.github.awesomedog.soma.infra.logging.Logging;

@AnalyzeClasses(
    packages = ArchitectureTest.BASE_PACKAGE,
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  static final String BASE_PACKAGE = "io.github.awesomedog.soma";

  @ArchTest
  static final ArchRule PACKAGE_DEPENDENCIES =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Command Runtime")
          .definedBy(BASE_PACKAGE + ".cli..", BASE_PACKAGE + ".exec..")
          .layer("HTTP")
          .definedBy(BASE_PACKAGE + ".http..")
          .layer("Application")
          .definedBy(BASE_PACKAGE + ".app..")
          .layer("Domain")
          .definedBy(BASE_PACKAGE + ".domain..")
          .layer("Infrastructure")
          .definedBy(BASE_PACKAGE + ".infra..")
          .layer("Support")
          .definedBy(BASE_PACKAGE + ".support..")
          .whereLayer("Command Runtime")
          .mayOnlyAccessLayers("Application", "Domain", "Support")
          .whereLayer("HTTP")
          .mayOnlyAccessLayers("Command Runtime", "Application", "Domain", "Support")
          .whereLayer("Application")
          .mayOnlyAccessLayers("Domain", "Support")
          .whereLayer("Domain")
          .mayOnlyAccessLayers("Support")
          .whereLayer("Infrastructure")
          .mayOnlyAccessLayers("Application", "Domain", "Support")
          .whereLayer("Support")
          .mayNotAccessAnyLayer()
          .ignoreDependency(CommandRunner.class, Logging.class)
          .because(
              "impl.md treats cli and exec as one command runtime layer; logging setup is an explicit composition exception");

  @ArchTest
  static final ArchRule STANDARD_OUT_IS_ADAPTED_BY_INVOCATION =
      noClasses()
          .that()
          .resideInAPackage(BASE_PACKAGE + "..")
          .and()
          .doNotHaveFullyQualifiedName(Invocation.class.getName())
          .should()
          .accessField(System.class, "out")
          .because("impl.md requires command results to use Invocation.out()");

  @ArchTest
  static final ArchRule STANDARD_ERROR_IS_ADAPTED_AT_PROCESS_BOUNDARIES =
      noClasses()
          .that()
          .resideInAPackage(BASE_PACKAGE + "..")
          .and()
          .doNotHaveFullyQualifiedName(Invocation.class.getName())
          .and()
          .doNotHaveFullyQualifiedName(SomaApplication.class.getName())
          .and()
          .doNotHaveFullyQualifiedName(Logging.class.getName())
          .should()
          .accessField(System.class, "err")
          .because(
              "impl.md requires diagnostics to use Invocation.err(); startup and logging failures occur before that adapter is available");
}
