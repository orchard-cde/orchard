package dev.orchard.core.model;

import java.util.List;
import java.util.Map;

public sealed interface LifecycleCommand permits LifecycleCommand.Sequential, LifecycleCommand.Parallel {
    record Sequential(List<String> args) implements LifecycleCommand {}
    record Parallel(Map<String, List<String>> steps) implements LifecycleCommand {}
}