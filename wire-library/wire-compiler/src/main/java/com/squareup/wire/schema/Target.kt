/*
 * Copyright 2018 Square Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.wire.schema

import com.squareup.javapoet.JavaFile
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.TypeSpec
import com.squareup.wire.WireCompiler
import com.squareup.wire.WireLogger
import com.squareup.wire.java.JavaGenerator
import com.squareup.wire.kotlin.KotlinGenerator
import com.squareup.wire.kotlin.RpcCallStyle
import com.squareup.wire.kotlin.RpcRole
import com.squareup.wire.schema.Target.SchemaHandler
import com.squareup.wire.swift.SwiftGenerator
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.io.IOException
import java.io.Serializable
import io.outfoxx.swiftpoet.FileSpec as SwiftFileSpec

sealed class Target : Serializable {
  /**
   * Proto types to include generated sources for. Types listed here will be generated for this
   * target and not for subsequent targets in the task.
   *
   * This list should contain package names (suffixed with `.*`) and type names only. It should
   * not contain member names.
   */
  abstract val includes: List<String>

  /**
   * Proto types to excluded generated sources for. Types listed here will not be generated for this
   * target.
   *
   * This list should contain package names (suffixed with `.*`) and type names only. It should
   * not contain member names.
   */
  abstract val excludes: List<String>

  /**
   * True if types emitted for this target should not also be emitted for other targets. Use this
   * to cause multiple outputs to be emitted for the same input type.
   */
  abstract val exclusive: Boolean

  /**
   * Directory where this target will write its output.
   *
   * In Gradle, when this class is serialized, this is relative to the project to improve build
   * cacheability. Callers must use [copyTarget] to resolve it to real path prior to use.
   */
  abstract val outDirectory: String

  /**
   * Returns a new Target object that is a copy of this one, but with the given fields updated.
   */
  abstract fun copyTarget(
    includes: List<String> = this.includes,
    excludes: List<String> = this.excludes,
    exclusive: Boolean = this.exclusive,
    outDirectory: String = this.outDirectory,
  ): Target

  /**
   * @param moduleName The module name for source generation which should correspond to a
   * subdirectory in the target's output directory. If null, generation should occur directly into
   * the root output directory.
   * @param upstreamTypes Types and their associated module name which were already generated. The
   * returned handler will be invoked only for types in [schema] which are NOT present in this map.
   */
  internal abstract fun newHandler(
    schema: Schema,
    moduleName: String?,
    upstreamTypes: Map<ProtoType, String>,
    fs: FileSystem,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler

  interface SchemaHandler {
    /** Returns the [Path] of the file which [type] will have been generated into. */
    fun handle(type: Type): Path?
    /** Returns the [Path]s of the files which [service] will have been generated into. */
    fun handle(service: Service): List<Path>
    /** Returns the [Path] of the files which [field] will have been generated into. */
    fun handle(extend: Extend, field: Field): Path?
    /**
     * This will handle all [Type]s and [Service]s of the `protoFile` in respect to the emitting
     * rules. If exclusive, the handled [Type]s and [Service]s should be added to the consumed set.
     * Consumed types and services themselves are to be omitted by this handler.
     */
    fun handle(
      protoFile: ProtoFile,
      emittingRules: EmittingRules,
      claimedDefinitions: ClaimedDefinitions,
      claimedPaths: ClaimedPaths,
      isExclusive: Boolean,
    ) {
      protoFile.types
        .filter { it !in claimedDefinitions && emittingRules.includes(it.type) }
        .forEach { type ->
          val generatedFilePath = handle(type)

          if (generatedFilePath != null) {
            claimedPaths.claim(generatedFilePath, type)
          }

          // We don't let other targets handle this one.
          if (isExclusive) claimedDefinitions.claim(type)
        }

      protoFile.services
        .filter { it !in claimedDefinitions && emittingRules.includes(it.type) }
        .forEach { service ->
          val generatedFilePaths = handle(service)

          for (generatedFilePath in generatedFilePaths) {
            claimedPaths.claim(generatedFilePath, service)
          }

          // We don't let other targets handle this one.
          if (isExclusive) claimedDefinitions.claim(service)
        }

      // TODO(jwilson): extend emitting rules to support include/exclude of extension fields.
      protoFile.extendList
        .flatMap { extend -> extend.fields.map { field -> extend to field } }
        .filter { it.second !in claimedDefinitions }
        .forEach { extendToField ->
          val (extend, field) = extendToField
          handle(extend, field)

          // We don't let other targets handle this one.
          if (isExclusive) claimedDefinitions.claim(field)
        }
    }
  }
}

/** Generate `.java` sources. */
data class JavaTarget(
  override val includes: List<String> = listOf("*"),
  override val excludes: List<String> = listOf(),

  override val exclusive: Boolean = true,

  override val outDirectory: String,

  /** True for emitted types to implement `android.os.Parcelable`. */
  val android: Boolean = false,

  /** True to enable the `androidx.annotation.Nullable` annotation where applicable. */
  val androidAnnotations: Boolean = false,

  /**
   * True to emit code that uses reflection for reading, writing, and toString methods which are
   * normally implemented with generated code.
   */
  val compact: Boolean = false,

  /** True to emit types for options declared on messages, fields, etc. */
  val emitDeclaredOptions: Boolean = true,

  /** True to emit annotations for options applied on messages, fields, etc. */
  val emitAppliedOptions: Boolean = true
) : Target() {
  override fun newHandler(
    schema: Schema,
    moduleName: String?,
    upstreamTypes: Map<ProtoType, String>,
    fs: FileSystem,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler {
    val profileName = if (android) "android" else "java"
    val profile = profileLoader.loadProfile(profileName, schema)
    val modulePath = run {
      val outPath = outDirectory.toPath()
      if (moduleName != null) {
        outPath / moduleName
      } else {
        outPath
      }
    }
    fs.createDirectories(modulePath)

    val javaGenerator = JavaGenerator.get(schema)
      .withProfile(profile)
      .withAndroid(android)
      .withAndroidAnnotations(androidAnnotations)
      .withCompact(compact)
      .withOptions(emitDeclaredOptions, emitAppliedOptions)

    return object : SchemaHandler {
      override fun handle(type: Type): Path? {
        if (JavaGenerator.builtInType(type.type)) return null

        val typeSpec = javaGenerator.generateType(type)
        val javaTypeName = javaGenerator.generatedTypeName(type)
        return write(javaTypeName, typeSpec, type.type, type.location)
      }

      override fun handle(service: Service): List<Path> {
        // Service handling isn't supporting in Java.
        return emptyList()
      }

      override fun handle(extend: Extend, field: Field): Path? {
        val typeSpec = javaGenerator.generateOptionType(extend, field) ?: return null
        val javaTypeName = javaGenerator.generatedTypeName(field)
        return write(javaTypeName, typeSpec, field.qualifiedName, field.location)
      }

      private fun write(
        javaTypeName: com.squareup.javapoet.ClassName,
        typeSpec: com.squareup.javapoet.TypeSpec,
        source: Any,
        location: Location
      ): Path {
        val javaFile = JavaFile.builder(javaTypeName.packageName(), typeSpec)
          .addFileComment("\$L", WireCompiler.CODE_GENERATED_BY_WIRE)
          .addFileComment("\nSource: \$L in \$L", source, location.withPathOnly())
          .build()
        val filePath = modulePath /
          javaFile.packageName.replace(".", "/") /
          "${javaTypeName.simpleName()}.java"

        logger.artifactHandled(modulePath, "${javaFile.packageName}.${javaFile.typeSpec.name}", "Java")
        try {
          fs.createDirectories(filePath.parent!!)
          fs.write(filePath) {
            writeUtf8(javaFile.toString())
          }
        } catch (e: IOException) {
          throw IOException(
            "Error emitting ${javaFile.packageName}.${javaFile.typeSpec.name} " +
              "to $outDirectory",
            e
          )
        }
        return filePath
      }
    }
  }

  override fun copyTarget(
    includes: List<String>,
    excludes: List<String>,
    exclusive: Boolean,
    outDirectory: String
  ): Target {
    return copy(
      includes = includes,
      excludes = excludes,
      exclusive = exclusive,
      outDirectory = outDirectory,
    )
  }
}

/** Generate `.kt` sources. */
data class KotlinTarget(
  override val includes: List<String> = listOf("*"),
  override val excludes: List<String> = listOf(),

  override val exclusive: Boolean = true,

  override val outDirectory: String,

  /** True for emitted types to implement `android.os.Parcelable`. */
  val android: Boolean = false,

  /** True for emitted types to implement APIs for easier migration from the Java target. */
  val javaInterop: Boolean = false,

  /** True to emit types for options declared on messages, fields, etc. */
  val emitDeclaredOptions: Boolean = true,

  /** True to emit annotations for options applied on messages, fields, etc. */
  val emitAppliedOptions: Boolean = true,

  /** Blocking or suspending. */
  val rpcCallStyle: RpcCallStyle = RpcCallStyle.SUSPENDING,

  /** Client or server. */
  val rpcRole: RpcRole = RpcRole.CLIENT,

  /** True for emitted services to implement one interface per RPC. */
  val singleMethodServices: Boolean = false,

  /**
   * If a oneof has more than or [boxOneOfsMinSize] fields, it will be generated using boxed oneofs
   * as defined in [OneOf][com.squareup.wire.OneOf].
   */
  val boxOneOfsMinSize: Int = 5_000,

  /** True to also generate gRPC server-compatible classes. Experimental feature. */
  val grpcServerCompatible: Boolean = false,

  /**
   * If present, generated services classes will use this as a suffix instead of inferring one
   * from the [rpcRole].
   */
  val nameSuffix: String? = null,
) : Target() {
  override fun newHandler(
    schema: Schema,
    moduleName: String?,
    upstreamTypes: Map<ProtoType, String>,
    fs: FileSystem,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler {
    val profileName = if (android) "android" else "java"
    val profile = profileLoader.loadProfile(profileName, schema)

    val modulePath = run {
      val outPath = outDirectory.toPath()
      if (moduleName != null) {
        outPath / moduleName
      } else {
        outPath
      }
    }
    fs.createDirectories(modulePath)

    val kotlinGenerator = KotlinGenerator(
      schema = schema,
      profile = profile,
      emitAndroid = android,
      javaInterop = javaInterop,
      emitDeclaredOptions = emitDeclaredOptions,
      emitAppliedOptions = emitAppliedOptions,
      rpcCallStyle = rpcCallStyle,
      rpcRole = rpcRole,
      boxOneOfsMinSize = boxOneOfsMinSize,
      grpcServerCompatible = grpcServerCompatible,
      nameSuffix = nameSuffix,
    )

    return object : SchemaHandler {
      override fun handle(type: Type): Path? {
        if (KotlinGenerator.builtInType(type.type)) return null

        val typeSpec = kotlinGenerator.generateType(type)
        val className = kotlinGenerator.generatedTypeName(type)
        return write(className, typeSpec, type.type, type.location)
      }

      override fun handle(service: Service): List<Path> {
        if (rpcRole === RpcRole.NONE) return emptyList()

        val generatedPaths = mutableListOf<Path>()

        if (singleMethodServices) {
          service.rpcs.forEach { rpc ->
            val map = kotlinGenerator.generateServiceTypeSpecs(service, rpc)
            for ((className, typeSpec) in map) {
              generatedPaths.add(write(className, typeSpec, service.type, service.location))
            }
          }
        } else {
          val map = kotlinGenerator.generateServiceTypeSpecs(service, null)
          for ((className, typeSpec) in map) {
            generatedPaths.add(write(className, typeSpec, service.type, service.location))
          }
        }

        return generatedPaths
      }

      override fun handle(extend: Extend, field: Field): Path? {
        val typeSpec = kotlinGenerator.generateOptionType(extend, field) ?: return null
        val name = kotlinGenerator.generatedTypeName(field)
        return write(name, typeSpec, field.qualifiedName, field.location)
      }

      private fun write(
        name: ClassName,
        typeSpec: TypeSpec,
        source: Any,
        location: Location
      ): Path {
        val kotlinFile = FileSpec.builder(name.packageName, name.simpleName)
          .addComment(WireCompiler.CODE_GENERATED_BY_WIRE)
          .addComment("\nSource: %L in %L", source, location.withPathOnly())
          .addType(typeSpec)
          .build()
        val filePath = modulePath /
          kotlinFile.packageName.replace(".", "/") /
          "${kotlinFile.name}.kt"
        val path = outDirectory.toPath()

        logger.artifactHandled(path, "${kotlinFile.packageName}.${(kotlinFile.members.first() as TypeSpec).name}", "Kotlin")
        try {
          fs.createDirectories(filePath.parent!!)
          fs.write(filePath) {
            writeUtf8(kotlinFile.toString())
          }
        } catch (e: IOException) {
          throw IOException("Error emitting ${kotlinFile.packageName}.$source to $outDirectory", e)
        }
        return filePath
      }
    }
  }

  override fun copyTarget(
    includes: List<String>,
    excludes: List<String>,
    exclusive: Boolean,
    outDirectory: String
  ): Target {
    return copy(
      includes = includes,
      excludes = excludes,
      exclusive = exclusive,
      outDirectory = outDirectory,
    )
  }
}

data class SwiftTarget(
  override val includes: List<String> = listOf("*"),
  override val excludes: List<String> = listOf(),
  override val exclusive: Boolean = true,
  override val outDirectory: String
) : Target() {
  override fun newHandler(
    schema: Schema,
    moduleName: String?,
    upstreamTypes: Map<ProtoType, String>,
    fs: FileSystem,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler {
    val modulePath = run {
      val outPath = outDirectory.toPath()
      if (moduleName != null) {
        outPath / moduleName
      } else {
        outPath
      }
    }
    fs.createDirectories(modulePath)

    val generator = SwiftGenerator(schema, upstreamTypes)
    return object : SchemaHandler {
      override fun handle(type: Type): Path? {
        if (SwiftGenerator.builtInType(type.type)) return null

        val typeName = generator.generatedTypeName(type)
        val swiftFile = SwiftFileSpec.builder(typeName.moduleName, typeName.simpleName)
          .addComment(WireCompiler.CODE_GENERATED_BY_WIRE)
          .addComment("\nSource: %L in %L", type.type, type.location.withPathOnly())
          .indent("    ")
          .apply {
            generator.generateTypeTo(type, this)
          }
          .build()

        val filePath = modulePath / "${swiftFile.name}.swift"
        try {
          fs.write(filePath) {
            writeUtf8(swiftFile.toString())
          }
        } catch (e: IOException) {
          throw IOException(
            "Error emitting ${swiftFile.moduleName}.${typeName.canonicalName} to $modulePath", e
          )
        }

        logger.artifactHandled(modulePath, "${swiftFile.moduleName}.${typeName.canonicalName}", "Swift")
        return filePath
      }

      override fun handle(service: Service) = emptyList<Path>()
      override fun handle(extend: Extend, field: Field): Path? = null
    }
  }

  override fun copyTarget(
    includes: List<String>,
    excludes: List<String>,
    exclusive: Boolean,
    outDirectory: String
  ): Target {
    return copy(
      includes = includes,
      excludes = excludes,
      exclusive = exclusive,
      outDirectory = outDirectory,
    )
  }
}

data class ProtoTarget(
  override val outDirectory: String
) : Target() {
  override val includes: List<String> = listOf()
  override val excludes: List<String> = listOf()
  override val exclusive: Boolean = false

  override fun newHandler(
    schema: Schema,
    moduleName: String?,
    upstreamTypes: Map<ProtoType, String>,
    fs: FileSystem,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler {
    val modulePath = run {
      val outPath = outDirectory.toPath()
      if (moduleName != null) {
        outPath / moduleName
      } else {
        outPath
      }
    }
    fs.createDirectories(modulePath)

    return object : SchemaHandler {
      override fun handle(type: Type): Path? = null
      override fun handle(service: Service): List<Path> = emptyList()
      override fun handle(extend: Extend, field: Field) = null
      override fun handle(
        protoFile: ProtoFile,
        emittingRules: EmittingRules,
        claimedDefinitions: ClaimedDefinitions,
        claimedPaths: ClaimedPaths,
        isExclusive: Boolean
      ) {
        if (protoFile.isEmpty()) return

        val relativePath = protoFile.location.path
          .substringBeforeLast("/", missingDelimiterValue = ".")
        val outputDirectory = modulePath / relativePath
        val outputFilePath = outputDirectory / "${protoFile.name()}.proto"
        logger.artifactHandled(outputDirectory, protoFile.location.path, "Proto")

        try {
          fs.createDirectories(outputFilePath.parent!!)
          fs.write(outputFilePath) {
            writeUtf8(protoFile.toSchema())
          }
        } catch (e: IOException) {
          throw IOException("Error emitting $outputFilePath to $outDirectory", e)
        }
      }
    }
  }

  private fun ProtoFile.isEmpty() = types.isEmpty() && services.isEmpty() && extendList.isEmpty()

  override fun copyTarget(
    includes: List<String>,
    excludes: List<String>,
    exclusive: Boolean,
    outDirectory: String
  ): Target {
    return copy(
      outDirectory = outDirectory,
    )
  }
}

/**
 * Generate something custom defined by an external class.
 *
 * This API is currently unstable. We will be changing this API in the future.
 */
data class CustomTargetBeta(
  override val includes: List<String> = listOf("*"),
  override val excludes: List<String> = listOf(),
  override val exclusive: Boolean = true,
  override val outDirectory: String,
  val customHandler: CustomHandlerBeta,
) : Target() {
  override fun newHandler(
    schema: Schema,
    moduleName: String?,
    upstreamTypes: Map<ProtoType, String>,
    fs: FileSystem,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler {
    return customHandler.newHandler(schema, fs, outDirectory, logger, profileLoader, errorCollector)
  }

  override fun copyTarget(
    includes: List<String>,
    excludes: List<String>,
    exclusive: Boolean,
    outDirectory: String
  ): Target {
    return copy(
      includes = includes,
      excludes = excludes,
      exclusive = exclusive,
      outDirectory = outDirectory,
    )
  }
}

/**
 * Implementations of this interface must have a no-arguments public constructor.
 *
 * This API is currently unstable. We will be changing this API in the future.
 */
interface CustomHandlerBeta {
  fun newHandler(
    schema: Schema,
    fs: FileSystem,
    outDirectory: String,
    logger: WireLogger,
    profileLoader: ProfileLoader
  ): Target.SchemaHandler

  // TODO: move to SchemaHandler as part of https://github.com/square/wire/issues/2077
  fun newHandler(
    schema: Schema,
    fs: FileSystem,
    outDirectory: String,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): Target.SchemaHandler = newHandler(schema, fs, outDirectory, logger, profileLoader)
}

/**
 * Create and return an instance of [customHandlerClass].
 *
 * @param customHandlerClass a fully qualified class name for a class that implements
 *     [CustomHandlerBeta]. The class must have a no-arguments public constructor.
 */
fun newCustomHandler(customHandlerClass: String): CustomHandlerBeta {
  return ClassNameCustomHandlerBeta(customHandlerClass)
}

/**
 * This custom handler is serializable (so Gradle can cache targets that use it). It works even if
 * the delegate handler class is itself not serializable.
 */
private class ClassNameCustomHandlerBeta(
  val customHandlerClass: String
) : CustomHandlerBeta, Serializable {
  @Transient private var cachedDelegate: CustomHandlerBeta? = null

  private val delegate: CustomHandlerBeta
    get() {
      val cachedResult = cachedDelegate
      if (cachedResult != null) return cachedResult

      val customHandlerType = try {
        Class.forName(customHandlerClass)
      } catch (exception: ClassNotFoundException) {
        throw IllegalArgumentException("Couldn't find CustomHandlerClass '$customHandlerClass'")
      }

      val constructor = try {
        customHandlerType.getConstructor()
      } catch (exception: NoSuchMethodException) {
        throw IllegalArgumentException("No public constructor on $customHandlerClass")
      }

      val result = constructor.newInstance() as? CustomHandlerBeta
        ?: throw IllegalArgumentException(
          "$customHandlerClass does not implement CustomHandlerBeta"
        )
      this.cachedDelegate = result
      return result
    }

  override fun newHandler(
    schema: Schema,
    fs: FileSystem,
    outDirectory: String,
    logger: WireLogger,
    profileLoader: ProfileLoader
  ): SchemaHandler {
    error("unexpected call")
  }

  override fun newHandler(
    schema: Schema,
    fs: FileSystem,
    outDirectory: String,
    logger: WireLogger,
    profileLoader: ProfileLoader,
    errorCollector: ErrorCollector,
  ): SchemaHandler = delegate.newHandler(schema, fs, outDirectory, logger, profileLoader, errorCollector)
}
