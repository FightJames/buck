/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.python;

import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.CommandTool;
import com.facebook.buck.rules.HasRuntimeDeps;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.args.SourcePathArg;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.facebook.buck.step.fs.MkdirStep;
import com.facebook.buck.step.fs.RmStep;
import com.facebook.buck.util.RichStream;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.stream.Stream;

public class PythonPackagedBinary extends PythonBinary implements HasRuntimeDeps {

  private final SourcePathRuleFinder ruleFinder;
  @AddToRuleKey private final Tool builder;
  @AddToRuleKey private final ImmutableList<String> buildArgs;
  private final Tool pathToPexExecuter;
  @AddToRuleKey private final String mainModule;
  @AddToRuleKey private final PythonEnvironment pythonEnvironment;
  @AddToRuleKey private final ImmutableSet<String> preloadLibraries;
  private final boolean cache;

  private PythonPackagedBinary(
      BuildRuleParams params,
      Supplier<? extends SortedSet<BuildRule>> originalDeclareDeps,
      SourcePathRuleFinder ruleFinder,
      PythonPlatform pythonPlatform,
      Tool builder,
      ImmutableList<String> buildArgs,
      Tool pathToPexExecuter,
      String pexExtension,
      PythonEnvironment pythonEnvironment,
      String mainModule,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries,
      boolean cache,
      boolean legacyOutputPath) {
    super(
        params,
        originalDeclareDeps,
        pythonPlatform,
        mainModule,
        components,
        preloadLibraries,
        pexExtension,
        legacyOutputPath);
    this.ruleFinder = ruleFinder;
    this.builder = builder;
    this.buildArgs = buildArgs;
    this.pathToPexExecuter = pathToPexExecuter;
    this.pythonEnvironment = pythonEnvironment;
    this.mainModule = mainModule;
    this.preloadLibraries = preloadLibraries;
    this.cache = cache;
  }

  static PythonPackagedBinary from(
      BuildRuleParams params,
      SourcePathRuleFinder ruleFinder,
      PythonPlatform pythonPlatform,
      Tool builder,
      ImmutableList<String> buildArgs,
      Tool pathToPexExecuter,
      String pexExtension,
      PythonEnvironment pythonEnvironment,
      String mainModule,
      PythonPackageComponents components,
      ImmutableSet<String> preloadLibraries,
      boolean cache,
      boolean legacyOutputPath) {
    return new PythonPackagedBinary(
        params
            .withDeclaredDeps(
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(components.getDeps(ruleFinder))
                    .addAll(builder.getDeps(ruleFinder))
                    .build())
            .withoutExtraDeps(),
        params.getDeclaredDeps(),
        ruleFinder,
        pythonPlatform,
        builder,
        buildArgs,
        pathToPexExecuter,
        pexExtension,
        pythonEnvironment,
        mainModule,
        components,
        preloadLibraries,
        cache,
        legacyOutputPath);
  }

  @Override
  public Tool getExecutableCommand() {
    return new CommandTool.Builder(pathToPexExecuter)
        .addArg(SourcePathArg.of(getSourcePathToOutput()))
        .build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {

    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    Path binPath = context.getSourcePathResolver().getRelativePath(getSourcePathToOutput());

    // Make sure the parent directory exists.
    steps.add(
        MkdirStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), binPath.getParent())));

    // Delete any other pex that was there (when switching between pex styles).
    steps.add(
        RmStep.of(
                BuildCellRelativePath.fromCellRelativePath(
                    context.getBuildCellRootPath(), getProjectFilesystem(), binPath))
            .withRecursive(true));

    Path workingDirectory =
        BuildTargets.getGenPath(
            getProjectFilesystem(), getBuildTarget(), "__%s__working_directory");

    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), workingDirectory)));

    SourcePathResolver resolver = context.getSourcePathResolver();

    // Generate and return the PEX build step.
    steps.add(
        new PexStep(
            getProjectFilesystem(),
            builder.getEnvironment(resolver),
            ImmutableList.<String>builder()
                .addAll(builder.getCommandPrefix(resolver))
                .addAll(buildArgs)
                .build(),
            pythonEnvironment.getPythonPath(),
            pythonEnvironment.getPythonVersion(),
            workingDirectory,
            binPath,
            mainModule,
            resolver.getMappedPaths(getComponents().getModules()),
            resolver.getMappedPaths(getComponents().getResources()),
            resolver.getMappedPaths(getComponents().getNativeLibraries()),
            ImmutableSet.copyOf(
                resolver.getAllAbsolutePaths(getComponents().getPrebuiltLibraries())),
            preloadLibraries,
            getComponents().isZipSafe().orElse(true)));

    // Record the executable package for caching.
    buildableContext.recordArtifact(binPath);

    return steps.build();
  }

  @Override
  public Stream<BuildTarget> getRuntimeDeps() {
    return RichStream.<BuildTarget>empty()
        .concat(super.getRuntimeDeps())
        .concat(pathToPexExecuter.getDeps(ruleFinder).stream().map(BuildRule::getBuildTarget));
  }

  @Override
  public boolean isCacheable() {
    return cache;
  }
}
