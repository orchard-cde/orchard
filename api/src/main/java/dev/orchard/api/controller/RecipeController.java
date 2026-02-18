package dev.orchard.api.controller;

import dev.orchard.api.dto.ApplyRecipeRequest;
import dev.orchard.api.dto.RecipeJobResponse;
import dev.orchard.api.dto.RecipeResponse;
import dev.orchard.api.service.GroveService;
import dev.orchard.core.model.Grove;
import dev.orchard.core.model.RecipeApplicationJob;
import dev.orchard.moderne.RecipeApplicationService;
import dev.orchard.moderne.RecipeCatalogService;
import dev.orchard.roots.entity.RecipeJobEntity;
import dev.orchard.roots.repository.RecipeJobRepository;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * REST controller for browsing OpenRewrite recipes and applying them to Groves.
 * Recipes are sourced from the Moderne SaaS API (if configured) or the built-in catalog.
 */
@RestController
@RequestMapping("/api")
public class RecipeController {

    private static final Logger log = LoggerFactory.getLogger(RecipeController.class);

    private final RecipeCatalogService recipeCatalogService;
    private final RecipeApplicationService recipeApplicationService;
    private final RecipeJobRepository recipeJobRepository;
    private final GroveService groveService;

    public RecipeController(
            RecipeCatalogService recipeCatalogService,
            RecipeApplicationService recipeApplicationService,
            RecipeJobRepository recipeJobRepository,
            GroveService groveService) {
        this.recipeCatalogService = recipeCatalogService;
        this.recipeApplicationService = recipeApplicationService;
        this.recipeJobRepository = recipeJobRepository;
        this.groveService = groveService;
    }

    /**
     * Lists all available OpenRewrite recipes.
     */
    @GetMapping("/recipes")
    public ResponseEntity<List<RecipeResponse>> listRecipes() {
        List<RecipeResponse> recipes = recipeCatalogService.listAll().stream()
            .map(RecipeResponse::fromModel)
            .toList();
        return ResponseEntity.ok(recipes);
    }

    /**
     * Searches for recipes matching the given query string.
     */
    @GetMapping("/recipes/search")
    public ResponseEntity<List<RecipeResponse>> searchRecipes(@RequestParam("q") String query) {
        List<RecipeResponse> recipes = recipeCatalogService.searchRecipes(query).stream()
            .map(RecipeResponse::fromModel)
            .toList();
        return ResponseEntity.ok(recipes);
    }

    /**
     * Applies an OpenRewrite recipe to a Grove's codebase.
     * The recipe runs asynchronously; poll GET /api/groves/{groveId}/recipes/{jobId} for status.
     */
    @PostMapping("/groves/{groveId}/recipes")
    public ResponseEntity<RecipeJobResponse> applyRecipe(
            @PathVariable UUID groveId,
            @Valid @RequestBody ApplyRecipeRequest request) {
        Optional<Grove> groveOpt = groveService.getGrove(groveId);
        if (groveOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        Grove grove = groveOpt.get();

        // Validate recipe exists
        if (recipeCatalogService.getRecipe(request.recipeId()).isEmpty()) {
            log.warn("Recipe not found: {}", request.recipeId());
            return ResponseEntity.badRequest().build();
        }

        // Create the job and persist it
        RecipeApplicationJob job = RecipeApplicationJob.create(groveId, request.recipeId());
        recipeJobRepository.save(RecipeJobEntity.fromModel(job));

        // Run the recipe asynchronously and update the job when complete
        recipeApplicationService.applyRecipe(grove, request.recipeId())
            .thenAccept(completedJob -> {
                RecipeApplicationJob finalJob = new RecipeApplicationJob(
                    job.id(), job.groveId(), job.recipeId(),
                    completedJob.state(), completedJob.changedFiles(),
                    completedJob.diff(), job.createdAt(), completedJob.completedAt()
                );
                recipeJobRepository.save(RecipeJobEntity.fromModel(finalJob));
                log.info("Recipe job {} completed with state {}", job.id(), completedJob.state());
            });

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(RecipeJobResponse.fromModel(job));
    }

    /**
     * Gets the current status of a recipe application job.
     */
    @GetMapping("/groves/{groveId}/recipes/{jobId}")
    public ResponseEntity<RecipeJobResponse> getRecipeJob(
            @PathVariable UUID groveId,
            @PathVariable UUID jobId) {
        return recipeJobRepository.findById(jobId)
            .filter(entity -> entity.getGroveId().equals(groveId))
            .map(entity -> ResponseEntity.ok(RecipeJobResponse.fromModel(entity.toModel())))
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Commits the changes made by a recipe application.
     */
    @PostMapping("/groves/{groveId}/recipes/{jobId}/commit")
    public ResponseEntity<RecipeJobResponse> commitRecipeChanges(
            @PathVariable UUID groveId,
            @PathVariable UUID jobId,
            @RequestBody(required = false) CommitRequest commitRequest) {
        Optional<Grove> groveOpt = groveService.getGrove(groveId);
        if (groveOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return recipeJobRepository.findById(jobId)
            .filter(entity -> entity.getGroveId().equals(groveId))
            .map(entity -> {
                RecipeApplicationJob job = entity.toModel();
                if (job.state() != dev.orchard.core.model.RecipeApplicationState.COMPLETED) {
                    return ResponseEntity.badRequest().<RecipeJobResponse>build();
                }
                try {
                    String message = commitRequest != null && commitRequest.message() != null
                        ? commitRequest.message()
                        : "Apply OpenRewrite recipe: " + job.recipeId();
                    recipeApplicationService.commitChanges(groveOpt.get(), message);
                    return ResponseEntity.ok(RecipeJobResponse.fromModel(job));
                } catch (Exception e) {
                    log.error("Failed to commit recipe changes for job {}: {}", jobId, e.getMessage());
                    return ResponseEntity.internalServerError().<RecipeJobResponse>build();
                }
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Discards the changes made by a recipe application.
     */
    @PostMapping("/groves/{groveId}/recipes/{jobId}/discard")
    public ResponseEntity<RecipeJobResponse> discardRecipeChanges(
            @PathVariable UUID groveId,
            @PathVariable UUID jobId) {
        Optional<Grove> groveOpt = groveService.getGrove(groveId);
        if (groveOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        return recipeJobRepository.findById(jobId)
            .filter(entity -> entity.getGroveId().equals(groveId))
            .map(entity -> {
                RecipeApplicationJob job = entity.toModel();
                if (job.state() != dev.orchard.core.model.RecipeApplicationState.COMPLETED) {
                    return ResponseEntity.badRequest().<RecipeJobResponse>build();
                }
                try {
                    recipeApplicationService.discardChanges(groveOpt.get());
                    return ResponseEntity.ok(RecipeJobResponse.fromModel(job));
                } catch (Exception e) {
                    log.error("Failed to discard recipe changes for job {}: {}", jobId, e.getMessage());
                    return ResponseEntity.internalServerError().<RecipeJobResponse>build();
                }
            })
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Request body for committing recipe changes with a custom message.
     */
    record CommitRequest(String message) {}
}
