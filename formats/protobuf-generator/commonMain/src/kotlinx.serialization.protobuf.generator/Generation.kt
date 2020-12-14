package kotlinx.serialization.protobuf.generator

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.protobuf.ProtoIntegerType
import kotlinx.serialization.protobuf.ProtoNumber
import kotlinx.serialization.protobuf.ProtoType

@ExperimentalSerializationApi
public fun generateProto(descriptors: List<SerialDescriptor>,
                         packageName: String? = null,
                         options: Map<String, String> = emptyMap()): String {
    val customTypes = findCustomTypes(descriptors)

    val builder = StringBuilder()
    builder.appendLine("""syntax = "proto2";""")
    builder.appendLine()
    packageName?.let {
        builder.append("package ").append(it).appendLine(';')
    }
    for ((optionName, optionValue) in options) {
        builder.append("option ").append(optionName).append(" = \"").append(optionValue).appendLine("\";")
    }

    generateCustomTypes(builder, customTypes)

    return builder.toString()
}

public class ProtoSchemeGenerationException : SerializationException {
    public constructor(message: String) : super(message)
    public constructor(message: String, cause: Throwable) : super(message, cause)
}


@ExperimentalSerializationApi
private fun findCustomTypes(descriptors: List<SerialDescriptor>): Map<String, SerialDescriptor> {
    val result = linkedMapOf<String, SerialDescriptor>()
    descriptors.forEach { addCustomTypeWithElements(it, result) }
    return result
}


@ExperimentalSerializationApi
private fun addCustomTypeWithElements(descriptor: SerialDescriptor, all: MutableMap<String, SerialDescriptor>) {
    when {
        descriptor.isProtobufScalar -> return
        descriptor.isProtobufStaticMessage ->
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
                for (childDescriptor in descriptor.elementDescriptors) {
                    addCustomTypeWithElements(childDescriptor, all)
                }
            }
        descriptor.isProtobufEnum -> {
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
            }
        }
        descriptor.isProtobufRepeated -> addCustomTypeWithElements(descriptor.getElementDescriptor(0), all)
        descriptor.isProtobufMap -> addCustomTypeWithElements(descriptor.getElementDescriptor(1), all)
        descriptor.isProtobufSealedMessage -> {
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
            }
            val contextualDescriptor = descriptor.getElementDescriptor(1)
            for (childDescriptor in contextualDescriptor.elementDescriptors) {
                addCustomTypeWithElements(childDescriptor, all)
            }
        }
        descriptor.isProtobufOpenMessage -> {
            if (!all.containsKey(descriptor.serialName)) {
                all[descriptor.serialName] = descriptor
            }
        }
        descriptor.isProtobufContextualMessage -> return
        else -> throw ProtoSchemeGenerationException("Unrecognized custom type with serial name "
                + "'${descriptor.serialName}' and kind '${descriptor.kind}'! Internal error.")
    }
}


@ExperimentalSerializationApi
private fun generateCustomTypes(builder: StringBuilder, customTypes: Map<String, SerialDescriptor>) {
    for ((_, descriptor) in customTypes) {
        builder.appendLine()
        when {
            descriptor.isProtobufEnum -> generateEnum(descriptor, builder)
            descriptor.isProtobufMessage -> generateMessage(descriptor, builder)
            else -> throw ProtoSchemeGenerationException(
                    "Custom type can be enum or message but found kind '${descriptor.kind}'! Internal error.")
        }
    }
}


@ExperimentalSerializationApi
private fun generateMessage(
        descriptor: SerialDescriptor,
        builder: StringBuilder) {
    builder.append("// serial name: ").appendLine(descriptor.serialName)

    builder.append("message ").append(descriptor.protobufCustomTypeName).appendLine(" {")
    for (index in 0 until descriptor.elementsCount) {
        val childDescriptor = descriptor.getElementDescriptor(index)
        val annotations = descriptor.getElementAnnotations(index)

        val fieldName = replaceIllegalSymbols(descriptor.getElementName(index))

        if (descriptor.isElementOptional(index)) {
            builder.appendLine("  // WARNING: field '$fieldName' has default value what not present in scheme")
            println("""WARNING: field '$fieldName' in serializable class '${descriptor.serialName}' """ +
                    "has default value which is not saved in proto scheme!")
        }

        try {
            when {
                childDescriptor.isProtobufNamedType -> generateNamedType(descriptor, childDescriptor, index, builder)
                childDescriptor.isProtobufMap -> generateMapType(childDescriptor, builder)
                childDescriptor.isProtobufRepeated -> generateListType(childDescriptor, builder)
                else -> throw ProtoSchemeGenerationException("Unprocessed message field type with serial name " +
                        "'${childDescriptor.serialName}' and kind '${childDescriptor.kind}'! Internal error.")
            }
        } catch (e: Exception) {
            throw ProtoSchemeGenerationException("An error occurred during type inference for field " +
                    "$fieldName of message ${descriptor.protobufCustomTypeName} (serial name ${descriptor.serialName})", e)
        }


        builder.append(' ')
        builder.append(fieldName)
        builder.append(" = ")
        val number = annotations.filterIsInstance<ProtoNumber>().singleOrNull()?.number ?: index + 1
        builder.append(number)
        builder.appendLine(';')
    }
    builder.appendLine('}')
}

@ExperimentalSerializationApi
private fun generateEnum(
        descriptor: SerialDescriptor,
        builder: StringBuilder) {
    val enumName = descriptor.protobufCustomTypeName
    builder.append("// serial name: ").appendLine(descriptor.serialName)
    builder.append("enum ").append(enumName).appendLine(" {")

    descriptor.elementDescriptors.forEachIndexed { number, element ->
        builder.append("  ").append(element.protobufEnumElementName).append(" = ").append(number).appendLine(';')
    }
    builder.appendLine('}')
}

@ExperimentalSerializationApi
private fun generateNamedType(messageDescriptor: SerialDescriptor, fieldDescriptor: SerialDescriptor, index: Int, builder: StringBuilder) {
    if (fieldDescriptor.isProtobufContextualMessage) {
        if (messageDescriptor.isProtobufSealedMessage) {
            builder.appendLine("  // decoded as message with type one of this type:")
            fieldDescriptor.elementDescriptors.forEachIndexed { _, childDescriptor ->
                builder.append("  //   message ").append(childDescriptor.protobufCustomTypeName).append(", serial name = ").appendLine(childDescriptor.serialName)
            }
        } else {
            builder.appendLine("  // contextual message type")
        }
    }

    builder.append("  ")
            .append(if (messageDescriptor.isElementOptional(index)) "optional " else "required ")
            .append(namedTypeName(fieldDescriptor, messageDescriptor.getElementAnnotations(index)))
}

@ExperimentalSerializationApi
private fun generateMapType(descriptor: SerialDescriptor, builder: StringBuilder) {
    builder.append("  map<")
    builder.append(protobufMapKeyType(descriptor.getElementDescriptor(0)))
    builder.append(", ")
    builder.append(protobufMapValueType(descriptor.getElementDescriptor(1)))
    builder.append(">")
}

@ExperimentalSerializationApi
private fun generateListType(descriptor: SerialDescriptor, builder: StringBuilder) {
    builder.append("  repeated ")
    builder.append(protobufRepeatedType(descriptor.getElementDescriptor(0)))
}


@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufNamedType: Boolean
    get() {
        return isProtobufScalar || isProtobufCustomType
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufCustomType: Boolean
    get() {
        return isProtobufMessage || isProtobufEnum
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufMessage: Boolean
    get() {
        return isProtobufStaticMessage || isProtobufOpenMessage || isProtobufSealedMessage || isProtobufContextualMessage
    }


@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufScalar: Boolean
    get() {
        return (kind is PrimitiveKind)
                || (kind is StructureKind.LIST && getElementDescriptor(0).kind === PrimitiveKind.BYTE)
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufStaticMessage: Boolean
    get() {
        return kind == StructureKind.CLASS || kind == StructureKind.OBJECT
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufOpenMessage: Boolean
    get() {
        return kind == PolymorphicKind.OPEN
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufSealedMessage: Boolean
    get() {
        return kind == PolymorphicKind.SEALED
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufContextualMessage: Boolean
    get() {
        return kind == SerialKind.CONTEXTUAL
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufRepeated: Boolean
    get() {
        return kind == StructureKind.LIST && getElementDescriptor(0).kind != PrimitiveKind.BYTE
    }

@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufMap: Boolean
    get() {
        return kind == StructureKind.MAP
    }


@ExperimentalSerializationApi
private val SerialDescriptor.isProtobufEnum: Boolean
    get() {
        return this.kind == SerialKind.ENUM
    }

@ExperimentalSerializationApi
private val SerialDescriptor.protobufCustomTypeName: String
    get() {
        return replaceIllegalSymbols(serialName.substringAfterLast('.', serialName))
    }

@ExperimentalSerializationApi
private val SerialDescriptor.protobufEnumElementName: String
    get() {
        return replaceIllegalSymbols(serialName.substringAfterLast('.', serialName))
    }


@ExperimentalSerializationApi
private fun scalarTypeName(descriptor: SerialDescriptor, annotations: List<Annotation> = emptyList()): String {
    val integerType = annotations.filterIsInstance<ProtoType>().firstOrNull()?.type ?: ProtoIntegerType.DEFAULT

    if (descriptor.kind is StructureKind.LIST && descriptor.getElementDescriptor(0).kind == PrimitiveKind.BYTE) {
        return "bytes"
    }

    return when (descriptor.kind as PrimitiveKind) {
        PrimitiveKind.BOOLEAN -> "bool"
        PrimitiveKind.BYTE, PrimitiveKind.CHAR, PrimitiveKind.SHORT, PrimitiveKind.INT ->
            when (integerType) {
                ProtoIntegerType.DEFAULT -> "int32"
                ProtoIntegerType.SIGNED -> "sint32"
                ProtoIntegerType.FIXED -> "fixed32"
            }
        PrimitiveKind.LONG ->
            when (integerType) {
                ProtoIntegerType.DEFAULT -> "int64"
                ProtoIntegerType.SIGNED -> "sint64"
                ProtoIntegerType.FIXED -> "fixed64"
            }
        PrimitiveKind.FLOAT -> "float"
        PrimitiveKind.DOUBLE -> "double"
        PrimitiveKind.STRING -> "string"
    }
}

@ExperimentalSerializationApi
private fun namedTypeName(descriptor: SerialDescriptor, annotations: List<Annotation>): String {
    return when {
        descriptor.isProtobufScalar -> scalarTypeName(descriptor, annotations)
        descriptor.isProtobufContextualMessage -> "bytes"
        descriptor.isProtobufCustomType -> descriptor.protobufCustomTypeName
        else -> throw ProtoSchemeGenerationException("Descriptor with serial name '${descriptor.serialName}' and kind " +
                "'${descriptor.kind}' isn't named protobuf type! Internal error.")
    }
}

@ExperimentalSerializationApi
private fun protobufMapKeyType(descriptor: SerialDescriptor): String {
    if (!descriptor.isProtobufScalar || descriptor.kind === PrimitiveKind.DOUBLE || descriptor.kind === PrimitiveKind.FLOAT) {
        throw ProtoSchemeGenerationException("Illegal type for map key! Serial name '${descriptor.serialName}' and " +
                "kind '${descriptor.kind}'." +
                "As map key type in protobuf allowed only scalar type except for floating point types and bytes.")
    }
    return scalarTypeName(descriptor)
}

@ExperimentalSerializationApi
private fun protobufMapValueType(descriptor: SerialDescriptor): String {
    if (descriptor.isProtobufRepeated) {
        throw ProtoSchemeGenerationException("List is not allowed as a map value type in protobuf!")
    }
    if (descriptor.isProtobufMap) {
        throw ProtoSchemeGenerationException("Map is not allowed as a map value type in protobuf!")
    }
    return namedTypeName(descriptor, emptyList())
}

@ExperimentalSerializationApi
private fun protobufRepeatedType(descriptor: SerialDescriptor): String {
    if (descriptor.isProtobufRepeated) {
        throw ProtoSchemeGenerationException("List is not allowed as a list element!")
    }
    if (descriptor.isProtobufMap) {
        throw ProtoSchemeGenerationException("Map is not allowed as a list element!")
    }
    return namedTypeName(descriptor, emptyList())
}

private fun replaceIllegalSymbols(serialName: String): String {
    return serialName.replace(Regex("[^A-Za-z0-9_]"), "_")
}
