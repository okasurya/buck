/*
 * Copyright 2014-present Facebook, Inc.
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

package com.facebook.buck.ocaml;

import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleCreationContext;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.CommonDescriptionArg;
import com.facebook.buck.rules.Description;
import com.facebook.buck.rules.HasDeclaredDeps;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathRuleFinder;
import com.facebook.buck.util.immutables.BuckStyleImmutable;
import com.facebook.buck.versions.VersionPropagator;
import com.google.common.collect.ImmutableList;
import java.nio.file.Path;
import java.util.Optional;
import org.immutables.value.Value;

/** Prebuilt OCaml library */
public class PrebuiltOcamlLibraryDescription
    implements Description<PrebuiltOcamlLibraryDescriptionArg>,
        VersionPropagator<PrebuiltOcamlLibraryDescriptionArg> {

  @Override
  public Class<PrebuiltOcamlLibraryDescriptionArg> getConstructorArgType() {
    return PrebuiltOcamlLibraryDescriptionArg.class;
  }

  @Override
  public OcamlLibrary createBuildRule(
      BuildRuleCreationContext context,
      BuildTarget buildTarget,
      BuildRuleParams params,
      final PrebuiltOcamlLibraryDescriptionArg args) {

    final boolean bytecodeOnly = args.getBytecodeOnly();

    final String libDir = args.getLibDir();

    final String nativeLib = args.getNativeLib();
    final String bytecodeLib = args.getBytecodeLib();
    final ImmutableList<String> cLibs = args.getCLibs();

    final Path libPath = buildTarget.getBasePath().resolve(libDir);
    final Path includeDir = libPath.resolve(args.getIncludeDir());

    ProjectFilesystem projectFilesystem = context.getProjectFilesystem();
    final Optional<SourcePath> staticNativeLibraryPath =
        bytecodeOnly
            ? Optional.empty()
            : Optional.of(PathSourcePath.of(projectFilesystem, libPath.resolve(nativeLib)));
    final SourcePath staticBytecodeLibraryPath =
        PathSourcePath.of(projectFilesystem, libPath.resolve(bytecodeLib));
    final ImmutableList<SourcePath> staticCLibraryPaths =
        cLibs
            .stream()
            .map(input -> PathSourcePath.of(projectFilesystem, libPath.resolve(input)))
            .collect(ImmutableList.toImmutableList());

    final SourcePath bytecodeLibraryPath =
        PathSourcePath.of(projectFilesystem, libPath.resolve(bytecodeLib));

    SourcePathRuleFinder ruleFinder = new SourcePathRuleFinder(context.getBuildRuleResolver());

    return new PrebuiltOcamlLibrary(
        buildTarget,
        projectFilesystem,
        params,
        ruleFinder,
        staticNativeLibraryPath,
        staticBytecodeLibraryPath,
        staticCLibraryPaths,
        bytecodeLibraryPath,
        libPath,
        includeDir);
  }

  @BuckStyleImmutable
  @Value.Immutable
  abstract static class AbstractPrebuiltOcamlLibraryDescriptionArg
      implements CommonDescriptionArg, HasDeclaredDeps {

    @Value.Default
    String getLibDir() {
      return "lib";
    }

    @Value.Default
    String getIncludeDir() {
      return "";
    }

    @Value.Default
    String getLibName() {
      return getName();
    }

    @Value.Default
    String getNativeLib() {
      return String.format("%s.cmxa", getLibName());
    }

    @Value.Default
    String getBytecodeLib() {
      return String.format("%s.cma", getLibName());
    }

    abstract ImmutableList<String> getCLibs();

    @Value.Default
    boolean getBytecodeOnly() {
      return false;
    }
  }
}
