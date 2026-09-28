package com.vcut.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.vcut.api")
class ArchitectureTest {

  @ArchTest
  static final ArchRule domainMustNotDependOnFrameworks =
      classes()
          .that()
          .resideInAnyPackage("com.vcut.api..domain..")
          .should()
          .onlyDependOnClassesThat()
          .resideOutsideOfPackages(
              "org.springframework..", "org.hibernate..", "jakarta.persistence..", "java.sql..");
}
