package dev.orchard.moderne;

import dev.orchard.core.model.Recipe;

import java.util.List;
import java.util.Optional;

/**
 * A curated, static catalog of popular OpenRewrite recipes.
 * Provides a useful default set of recipes even without a Moderne API key,
 * allowing Cultivators to apply common code transformations to their Groves.
 */
public final class BuiltinRecipeCatalog {

    private BuiltinRecipeCatalog() {}

    private static final List<Recipe> RECIPES = List.of(
        new Recipe(
            "org.openrewrite.staticanalysis.CommonStaticAnalysis",
            "Common Static Analysis",
            "Applies a collection of 50+ static analysis fixes including removing unnecessary " +
                "parentheses, simplifying boolean expressions, using diamond operators, and more. " +
                "A great first recipe to run on any Java codebase.",
            "Static Analysis",
            List.of("java", "static-analysis", "cleanup", "best-practices"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.migrate.UpgradeToJava21",
            "Upgrade to Java 21",
            "Migrates Java source code from older Java versions to Java 21, applying changes " +
                "for records, sealed classes, pattern matching for instanceof, switch expressions, " +
                "text blocks, and other modern Java features.",
            "Java Migration",
            List.of("java", "java-21", "migration", "modernization"),
            List.of(
                new Recipe.RecipeOption("addParenthesesForClarity", "boolean",
                    "Add parentheses to clarify operator precedence", false, "false")
            )
        ),
        new Recipe(
            "org.openrewrite.java.spring.boot3.UpgradeSpringBoot_3_4",
            "Upgrade to Spring Boot 3.4",
            "Migrates a Spring Boot application to version 3.4.x. Includes Jakarta EE namespace " +
                "migration, updated Spring properties, deprecated API replacements, and Spring " +
                "Security configuration updates.",
            "Spring",
            List.of("java", "spring", "spring-boot", "spring-boot-3", "migration"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.migrate.jakarta.JakartaEE10",
            "Migrate to Jakarta EE 10",
            "Migrates Java EE applications to Jakarta EE 10, including namespace changes from " +
                "javax.* to jakarta.*, updated dependency coordinates, and API adjustments " +
                "for Servlet, JPA, Bean Validation, and other Jakarta specifications.",
            "Jakarta EE",
            List.of("java", "jakarta", "javaee", "migration", "enterprise"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.testing.junit5.JUnit5BestPractices",
            "JUnit 5 Best Practices",
            "Applies JUnit 5 best practices including migrating from JUnit 4, using " +
                "assertAll for grouped assertions, preferring assertThrows over try-catch, " +
                "and adopting parameterized tests where applicable.",
            "Testing",
            List.of("java", "junit", "junit-5", "testing", "best-practices"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.security.JavaSecurityBestPractices",
            "Java Security Best Practices",
            "Applies security best practices including fixing potential SQL injection " +
                "vulnerabilities, replacing insecure random number generators, using " +
                "secure XML parsing configurations, and addressing common OWASP findings.",
            "Security",
            List.of("java", "security", "owasp", "best-practices"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.format.AutoFormat",
            "Auto-Format Java Code",
            "Automatically formats Java source code according to standard conventions. " +
                "Normalizes indentation, spacing, line breaks, and import ordering to " +
                "produce consistently formatted code across the entire project.",
            "Formatting",
            List.of("java", "formatting", "style", "code-quality"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.cleanup.UnnecessaryParentheses",
            "Remove Unnecessary Parentheses",
            "Removes unnecessary parentheses from Java expressions where operator " +
                "precedence makes them redundant. Improves code readability without " +
                "changing behavior.",
            "Cleanup",
            List.of("java", "cleanup", "readability"),
            List.of()
        ),
        new Recipe(
            "org.openrewrite.java.OrderImports",
            "Order Java Imports",
            "Reorders Java import statements according to standard conventions. Groups " +
                "imports by package, separates static imports, removes unused imports, " +
                "and ensures a consistent ordering across all source files.",
            "Formatting",
            List.of("java", "imports", "formatting", "style"),
            List.of(
                new Recipe.RecipeOption("removeUnused", "boolean",
                    "Remove unused imports", false, "true"),
                new Recipe.RecipeOption("layout", "String",
                    "Import layout style (e.g., intellij, eclipse)", false, "intellij")
            )
        ),
        new Recipe(
            "org.openrewrite.java.spring.boot3.SpringBoot3BestPractices",
            "Spring Boot 3 Best Practices",
            "Applies Spring Boot 3 best practices including using constructor injection, " +
                "replacing deprecated APIs, adopting Spring Boot 3 conventions for " +
                "configuration properties, and modernizing test configurations.",
            "Spring",
            List.of("java", "spring", "spring-boot", "spring-boot-3", "best-practices"),
            List.of()
        )
    );

    /**
     * Returns the complete list of built-in recipes.
     */
    public static List<Recipe> all() {
        return RECIPES;
    }

    /**
     * Searches the built-in catalog for recipes matching the given query.
     * Matches against recipe ID, name, description, category, and tags (case-insensitive).
     */
    public static List<Recipe> search(String query) {
        if (query == null || query.isBlank()) {
            return RECIPES;
        }
        String lowerQuery = query.toLowerCase();
        return RECIPES.stream()
            .filter(recipe ->
                recipe.id().toLowerCase().contains(lowerQuery) ||
                recipe.name().toLowerCase().contains(lowerQuery) ||
                recipe.description().toLowerCase().contains(lowerQuery) ||
                recipe.category().toLowerCase().contains(lowerQuery) ||
                recipe.tags().stream().anyMatch(tag -> tag.toLowerCase().contains(lowerQuery))
            )
            .toList();
    }

    /**
     * Retrieves a specific recipe by its ID from the built-in catalog.
     */
    public static Optional<Recipe> findById(String recipeId) {
        return RECIPES.stream()
            .filter(recipe -> recipe.id().equals(recipeId))
            .findFirst();
    }
}
