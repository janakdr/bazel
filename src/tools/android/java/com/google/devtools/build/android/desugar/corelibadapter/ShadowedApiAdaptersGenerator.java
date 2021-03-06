/*
 * Copyright 2020 The Bazel Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.devtools.build.android.desugar.corelibadapter;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.objectweb.asm.Opcodes.ACC_ABSTRACT;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SYNTHETIC;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.devtools.build.android.desugar.io.FileContentProvider;
import com.google.devtools.build.android.desugar.langmodel.ClassName;
import com.google.devtools.build.android.desugar.langmodel.InvocationSiteTransformationRecord;
import com.google.devtools.build.android.desugar.langmodel.MethodDeclInfo;
import com.google.devtools.build.android.desugar.langmodel.MethodInvocationSite;
import com.google.devtools.build.android.desugar.langmodel.MethodKey;
import java.io.ByteArrayInputStream;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates type adapter classes with methods that bridge the interactions between
 * desugared-mirrored types and their desugar-shadowed built-in types, and delivers the generated
 * classes to runtime library.
 */
public final class ShadowedApiAdaptersGenerator {

  private static final int TYPE_ADAPTER_CLASS_ACCESS = ACC_PUBLIC | ACC_ABSTRACT | ACC_SYNTHETIC;
  private static final int TYPE_CONVERSION_METHOD_ACCESS = ACC_PUBLIC | ACC_STATIC;

  /** A pre-collected record that tracks the adapter method requests from invocation sites. */
  private final InvocationSiteTransformationRecord invocationAdapterSites;

  /** A record with evolving map values that track adapter classes to be generated. */
  private final ImmutableMap<ClassName, ClassWriter> typeAdapters;

  /** The public API that provides the file content of generate adapter classes. */
  public static ImmutableList<FileContentProvider<ByteArrayInputStream>> generateAdapterClasses(
      InvocationSiteTransformationRecord callSiteTransformations) {
    return emitClassWriters(callSiteTransformations)
        .emitAdapterMethods()
        .closeClassWriters()
        .provideFileContents();
  }

  private ShadowedApiAdaptersGenerator(
      InvocationSiteTransformationRecord invocationAdapterSites,
      ImmutableMap<ClassName, ClassWriter> typeAdapters) {
    this.invocationAdapterSites = invocationAdapterSites;
    this.typeAdapters = typeAdapters;
  }

  private static ShadowedApiAdaptersGenerator emitClassWriters(
      InvocationSiteTransformationRecord callSiteTransformations) {
    return new ShadowedApiAdaptersGenerator(
        callSiteTransformations,
        callSiteTransformations.record().stream()
            .map(ShadowedApiAdapterHelper::getAdapterInvocationSite)
            .map(MethodInvocationSite::owner)
            .distinct()
            .collect(
                toImmutableMap(
                    className -> className,
                    ShadowedApiAdaptersGenerator::createAdapterClassWriter)));
  }

  private static ClassWriter createAdapterClassWriter(ClassName className) {
    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
    cw.visit(
        Opcodes.V1_7,
        TYPE_ADAPTER_CLASS_ACCESS,
        className.binaryName(),
        /* signature= */ null,
        /* superName= */ "java/lang/Object",
        /* interfaces= */ new String[0]);
    return cw;
  }

  private ShadowedApiAdaptersGenerator emitAdapterMethods() {
    for (MethodInvocationSite invocationSite : invocationAdapterSites.record()) {
      MethodInvocationSite adapterSite =
          ShadowedApiAdapterHelper.getAdapterInvocationSite(invocationSite);
      ClassName adapterOwner = adapterSite.owner();
      ClassWriter cv =
          checkNotNull(
              typeAdapters.get(adapterOwner),
              "Expected a class writer present before writing its methods. Requested adapter"
                  + " owner: (%s). Available adapter owners: (%s).",
              adapterOwner,
              typeAdapters);
      MethodKey adapterMethodKey = adapterSite.method();
      MethodDeclInfo adapterMethodDecl =
          MethodDeclInfo.create(
              adapterMethodKey,
              TYPE_ADAPTER_CLASS_ACCESS,
              TYPE_CONVERSION_METHOD_ACCESS,
              /* signature= */ null,
              /* exceptions= */ new String[] {});
      MethodVisitor mv = adapterMethodDecl.accept(cv);
      mv.visitCode();

      int slotOffset = 0;
      for (Type argType : adapterMethodDecl.argumentTypes()) {
        ClassName argTypeName = ClassName.create(argType);
        mv.visitVarInsn(argType.getOpcode(Opcodes.ILOAD), slotOffset);
        if (argTypeName.isDesugarMirroredType()) {
          MethodInvocationSite conversion =
              ShadowedApiAdapterHelper.mirroredToShadowedTypeConversionSite(argTypeName);
          conversion.accept(mv);
        }
        slotOffset += argType.getSize();
      }

      invocationSite.accept(mv);

      ClassName adapterReturnTypeName = adapterMethodDecl.returnTypeName();
      if (adapterReturnTypeName.isDesugarMirroredType()) {
        MethodInvocationSite conversion =
            ShadowedApiAdapterHelper.shadowedToMirroredTypeConversionSite(
                adapterReturnTypeName.mirroredToShadowed());
        conversion.accept(mv);
      }

      mv.visitInsn(adapterMethodDecl.returnType().getOpcode(Opcodes.IRETURN));

      mv.visitMaxs(slotOffset, slotOffset);
      mv.visitEnd();
    }
    return this;
  }

  private ShadowedApiAdaptersGenerator closeClassWriters() {
    typeAdapters.values().forEach(ClassVisitor::visitEnd);
    return this;
  }

  private ImmutableList<FileContentProvider<ByteArrayInputStream>> provideFileContents() {
    return typeAdapters.entrySet().stream()
        .map(
            e ->
                new FileContentProvider<>(
                    e.getKey().classFilePathName(),
                    () -> new ByteArrayInputStream(e.getValue().toByteArray())))
        .collect(toImmutableList());
  }
}
