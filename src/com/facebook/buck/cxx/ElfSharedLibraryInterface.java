/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.cxx;

import com.facebook.buck.cxx.toolchain.elf.ElfDynamicSection;
import com.facebook.buck.io.BuildCellRelativePath;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargets;
import com.facebook.buck.rules.AbstractBuildRuleWithDeclaredAndExtraDeps;
import com.facebook.buck.rules.AddToRuleKey;
import com.facebook.buck.rules.BuildContext;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.BuildableContext;
import com.facebook.buck.rules.BuildableSupport;
import com.facebook.buck.rules.CacheableBuildRule;
import com.facebook.buck.rules.ExplicitBuildTargetSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.rules.keys.SupportsInputBasedRuleKey;
import com.facebook.buck.step.Step;
import com.facebook.buck.step.fs.MakeCleanDirectoryStep;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import java.nio.file.Path;
import java.util.Optional;

/** Build a shared library interface from an ELF shared library. */
class ElfSharedLibraryInterface extends AbstractBuildRuleWithDeclaredAndExtraDeps
    implements SupportsInputBasedRuleKey, CacheableBuildRule {

  private SourcePathResolver pathResolver;

  @AddToRuleKey private final Tool objcopy;

  @AddToRuleKey private final SourcePath input;

  @AddToRuleKey private final boolean removeUndefinedSymbols;

  private ElfSharedLibraryInterface(
      BuildTarget buildTarget,
      ProjectFilesystem projectFilesystem,
      BuildRuleParams buildRuleParams,
      SourcePathResolver resolver,
      Tool objcopy,
      SourcePath input,
      boolean removeUndefinedSymbols) {
    super(buildTarget, projectFilesystem, buildRuleParams);
    this.pathResolver = resolver;
    this.objcopy = objcopy;
    this.input = input;
    this.removeUndefinedSymbols = removeUndefinedSymbols;
  }

  public static ElfSharedLibraryInterface from(
      BuildTarget target,
      ProjectFilesystem projectFilesystem,
      SourcePathResolver resolver,
      SourcePathRuleFinder ruleFinder,
      Tool objcopy,
      SourcePath input,
      boolean removeUndefinedSymbols) {
    return new ElfSharedLibraryInterface(
        target,
        projectFilesystem,
        new BuildRuleParams(
            () ->
                ImmutableSortedSet.<BuildRule>naturalOrder()
                    .addAll(BuildableSupport.getDepsCollection(objcopy, ruleFinder))
                    .addAll(ruleFinder.filterBuildRuleInputs(input))
                    .build(),
            ImmutableSortedSet::of,
            ImmutableSortedSet.of()),
        resolver,
        objcopy,
        input,
        removeUndefinedSymbols);
  }

  private Path getOutputDir() {
    return BuildTargets.getGenPath(getProjectFilesystem(), getBuildTarget(), "%s");
  }

  private String getSharedAbiLibraryName() {
    return pathResolver.getRelativePath(input).getFileName().toString();
  }

  // We only care about sections relevant to dynamic linking.
  private ImmutableSet<String> getSections() {
    ImmutableSet.Builder<String> sections = ImmutableSet.builder();
    sections.add(".dynamic", ".dynsym", ".dynstr", ".gnu.version", ".gnu.version_d");
    // The `.gnu.version_r` contains version information about undefined symbols, and so is only
    // relevant if we're not removing undefined symbols.
    if (!removeUndefinedSymbols) {
      sections.add(".gnu.version_r");
    }
    return sections.build();
  }

  @Override
  public ImmutableList<Step> getBuildSteps(
      BuildContext context, BuildableContext buildableContext) {
    Path output = getOutputDir().resolve(getSharedAbiLibraryName());
    Path outputScratch = getOutputDir().resolve(getSharedAbiLibraryName() + ".scratch");
    buildableContext.recordArtifact(output);
    ImmutableList.Builder<Step> steps = ImmutableList.builder();
    steps.addAll(
        MakeCleanDirectoryStep.of(
            BuildCellRelativePath.fromCellRelativePath(
                context.getBuildCellRootPath(), getProjectFilesystem(), getOutputDir())));
    steps.add(
        new ElfExtractSectionsStep(
            getBuildTarget(),
            objcopy.getCommandPrefix(context.getSourcePathResolver()),
            getSections(),
            context.getSourcePathResolver().getFilesystem(input),
            context.getSourcePathResolver().getRelativePath(input),
            getProjectFilesystem(),
            outputScratch),
        ElfSymbolTableScrubberStep.of(
            getProjectFilesystem(),
            outputScratch,
            /* section */ ".dynsym",
            /* versymSection */ Optional.of(".gnu.version"),
            /* allowMissing */ false,
            /* scrubUndefinedSymbols */ removeUndefinedSymbols),
        ElfSymbolTableScrubberStep.of(
            getProjectFilesystem(),
            outputScratch,
            /* section */ ".symtab",
            /* versymSection */ Optional.empty(),
            /* allowMissing */ true,
            /* scrubUndefinedSymbols */ true),
        ElfDynamicSectionScrubberStep.of(
            getProjectFilesystem(),
            outputScratch,
            // When scrubbing undefined symbols, drop the `DT_NEEDED` tags from the whitelist, as
            // these leak information about undefined references in the shared library.
            /* whitelistedTags */ removeUndefinedSymbols
                ? ImmutableSet.of(ElfDynamicSection.DTag.DT_SONAME)
                : ImmutableSet.of(
                    ElfDynamicSection.DTag.DT_NEEDED, ElfDynamicSection.DTag.DT_SONAME),
            /* removeScrubbedTags */ removeUndefinedSymbols),
        ElfScrubFileHeaderStep.of(getProjectFilesystem(), outputScratch));
    // If we're removing undefined symbols, rewrite the dynamic string table so that strings for
    // undefined symbol names are removed.
    if (removeUndefinedSymbols) {
      steps.add(ElfRewriteDynStrSectionStep.of(getProjectFilesystem(), outputScratch));
    }
    steps.add(
        ElfCompactSectionsStep.of(
            getBuildTarget(),
            objcopy.getCommandPrefix(context.getSourcePathResolver()),
            getProjectFilesystem(),
            outputScratch,
            getProjectFilesystem(),
            output),
        ElfClearProgramHeadersStep.of(getProjectFilesystem(), output));
    return steps.build();
  }

  @Override
  public SourcePath getSourcePathToOutput() {
    return ExplicitBuildTargetSourcePath.of(
        getBuildTarget(), getOutputDir().resolve(getSharedAbiLibraryName()));
  }

  @Override
  public void updateBuildRuleResolver(
      BuildRuleResolver ruleResolver,
      SourcePathRuleFinder ruleFinder,
      SourcePathResolver pathResolver) {
    this.pathResolver = pathResolver;
  }
}
