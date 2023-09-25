package org.openapitools.openapidiff.core.compare.schemadiffresult;

import static java.util.Optional.ofNullable;
import static org.openapitools.openapidiff.core.utils.ChangedUtils.isChanged;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.Schema;
import java.util.*;
import java.util.stream.Collectors;
import org.openapitools.openapidiff.core.compare.ListDiff;
import org.openapitools.openapidiff.core.compare.MapKeyDiff;
import org.openapitools.openapidiff.core.compare.OpenApiDiff;
import org.openapitools.openapidiff.core.model.Change;
import org.openapitools.openapidiff.core.model.Changed;
import org.openapitools.openapidiff.core.model.ChangedOneOfSchema;
import org.openapitools.openapidiff.core.model.ChangedSchema;
import org.openapitools.openapidiff.core.model.DiffContext;
import org.openapitools.openapidiff.core.model.deferred.DeferredBuilder;
import org.openapitools.openapidiff.core.model.deferred.DeferredChanged;
import org.openapitools.openapidiff.core.model.deferred.RecursiveSchemaSet;
import org.openapitools.openapidiff.core.model.schema.*;
import org.openapitools.openapidiff.core.utils.RefPointer;
import org.openapitools.openapidiff.core.utils.RefType;

public class SchemaDiffResult {

  private static final RefPointer<Schema<?>> refPointer = new RefPointer<>(RefType.SCHEMAS);
  protected ChangedSchema changedSchema;
  protected OpenApiDiff openApiDiff;

  public SchemaDiffResult(OpenApiDiff openApiDiff) {
    this.openApiDiff = openApiDiff;
    this.changedSchema = new ChangedSchema();
  }

  public SchemaDiffResult(String type, OpenApiDiff openApiDiff) {
    this(openApiDiff);
    this.changedSchema.setType(type);
  }

  public <V extends Schema<X>, X> DeferredChanged<ChangedSchema> diff(
      RecursiveSchemaSet refSet,
      Components leftComponents,
      Components rightComponents,
      V left,
      V right,
      DiffContext context) {

    DeferredBuilder<Changed> builder = new DeferredBuilder<>();

    ChangedEnum<X> changedEnum =
        ListDiff.diff(new ChangedEnum<>(left.getEnum(), right.getEnum(), context));
    changedSchema
        .setContext(context)
        .setOldSchema(left)
        .setNewSchema(right)
        .setChangeDeprecated(
            !Boolean.TRUE.equals(left.getDeprecated())
                && Boolean.TRUE.equals(right.getDeprecated()))
        .setChangeTitle(!Objects.equals(left.getTitle(), right.getTitle()))
        .setRequired(
            ListDiff.diff(new ChangedRequired(left.getRequired(), right.getRequired(), context)))
        .setChangeDefault(!Objects.equals(left.getDefault(), right.getDefault()))
        .setEnumeration(changedEnum)
        .setChangeFormat(!Objects.equals(left.getFormat(), right.getFormat()))
        .setReadOnly(new ChangedReadOnly(left.getReadOnly(), right.getReadOnly(), context))
        .setWriteOnly(new ChangedWriteOnly(left.getWriteOnly(), right.getWriteOnly(), context))
        .setMaxLength(new ChangedMaxLength(left.getMaxLength(), right.getMaxLength(), context))
        .setNumericRange(
            new ChangedNumericRange(
                left.getMinimum(),
                right.getMinimum(),
                left.getMaximum(),
                right.getMaximum(),
                left.getExclusiveMinimum(),
                right.getExclusiveMinimum(),
                left.getExclusiveMaximum(),
                right.getExclusiveMaximum(),
                context));
    builder
        .with(
            openApiDiff
                .getExtensionsDiff()
                .diff(left.getExtensions(), right.getExtensions(), context))
        .ifPresent(changedSchema::setExtensions);
    builder
        .with(
            openApiDiff
                .getMetadataDiff()
                .diff(left.getDescription(), right.getDescription(), context))
        .ifPresent(changedSchema::setDescription);
    Map<String, Schema> leftProperties = left.getProperties();
    Map<String, Schema> rightProperties = right.getProperties();
    MapKeyDiff<String, Schema> propertyDiff = MapKeyDiff.diff(leftProperties, rightProperties);
    compareDiscriminatorMapping(refSet, leftComponents, rightComponents, left, right, context);
    for (String key : propertyDiff.getSharedKey()) {
      builder
          .with(
              openApiDiff
                  .getSchemaDiff()
                  .diff(
                      refSet,
                      leftProperties.get(key),
                      rightProperties.get(key),
                      required(context, key, right.getRequired())))
          .ifPresent(
              changedSchema1 -> changedSchema.getChangedProperties().put(key, changedSchema1));
    }
    compareAdditionalProperties(refSet, left, right, context, builder);
    changedSchema
        .getIncreasedProperties()
        .putAll(filterProperties(Change.Type.ADDED, propertyDiff.getIncreased(), context));
    changedSchema
        .getMissingProperties()
        .putAll(filterProperties(Change.Type.REMOVED, propertyDiff.getMissing(), context));
    return builder.build().mapOptional(values -> isApplicable(context));
  }

  private <V extends Schema<X>, X> void compareDiscriminatorMapping(
      RecursiveSchemaSet refSet,
      Components leftComponents,
      Components rightComponents,
      V left,
      V right,
      DiffContext context) {
    Map<String, ChangedSchema> changedMapping = new LinkedHashMap<>();
    Map<String, String> leftMapping = getMapping(left);
    Map<String, String> rightMapping = getMapping(right);

    MapKeyDiff<String, Schema> mappingDiff =
        MapKeyDiff.diff(
            getSchema(leftComponents, leftMapping), getSchema(rightComponents, rightMapping));

    DeferredBuilder<ChangedSchema> discriminatorChangedBuilder = new DeferredBuilder<>();
    for (String key : mappingDiff.getSharedKey()) {
      Schema<?> leftSchema = new Schema<>();
      leftSchema.set$ref(leftMapping.get(key));
      Schema<?> rightSchema = new Schema<>();
      rightSchema.set$ref(rightMapping.get(key));
      discriminatorChangedBuilder
          .with(
              openApiDiff
                  .getSchemaDiff()
                  .diff(refSet, leftSchema, rightSchema, context.copyWithRequired(true)))
          .ifPresent(schema -> changedMapping.put(key, schema));
    }
    discriminatorChangedBuilder.whenSet(
        discriminatorSchemas ->
            changedSchema.setOneOfSchema(
                new ChangedOneOfSchema(leftMapping, rightMapping, context)
                    .setIncreased(mappingDiff.getIncreased())
                    .setMissing(mappingDiff.getMissing())
                    .setChanged(changedMapping)));
  }

  protected Map<String, Schema> getSchema(Components components, Map<String, String> mapping) {
    Map<String, Schema> result = new LinkedHashMap<>();
    mapping.forEach(
        (key, value) -> result.put(key, refPointer.resolveRef(components, new Schema<>(), value)));
    return result;
  }

  protected <V extends Schema<X>, X> Map<String, String> getMapping(V schema) {
    Map<String, String> reverseMapping = new LinkedHashMap<>();
    if (schema.getDiscriminator() != null && schema.getDiscriminator().getMapping() != null) {
      for (String ref : schema.getDiscriminator().getMapping().keySet()) {
        reverseMapping.put(schema.getDiscriminator().getMapping().get(ref), ref);
      }
    }

    return reverseMapping.entrySet().stream()
        .collect(Collectors.toMap(Map.Entry::getValue, Map.Entry::getKey));
  }

  protected Optional<ChangedSchema> isApplicable(DiffContext context) {
    if (changedSchema.getReadOnly().isUnchanged()
        && changedSchema.getWriteOnly().isUnchanged()
        && !isPropertyApplicable(changedSchema.getNewSchema(), context)) {
      return Optional.empty();
    }
    return isChanged(changedSchema);
  }

  private Map<String, Schema<?>> filterProperties(
      Change.Type type, Map<String, Schema> properties, DiffContext context) {
    Map<String, Schema<?>> result = new LinkedHashMap<>();
    for (Map.Entry<String, Schema> entry : properties.entrySet()) {
      if (isPropertyApplicable(entry.getValue(), context)
          && openApiDiff
              .getExtensionsDiff()
              .isParentApplicable(
                  type,
                  entry.getValue(),
                  ofNullable(entry.getValue().getExtensions()).orElse(new LinkedHashMap<>()),
                  context)) {
        result.put(entry.getKey(), entry.getValue());
      } else {
        // Child property is not applicable, so required cannot be applied
        changedSchema.getRequired().getIncreased().remove(entry.getKey());
      }
    }
    return result;
  }

  private boolean isPropertyApplicable(Schema<?> schema, DiffContext context) {
    return !(context.isResponse() && Boolean.TRUE.equals(schema.getWriteOnly()))
        && !(context.isRequest() && Boolean.TRUE.equals(schema.getReadOnly()));
  }

  private DiffContext required(DiffContext context, String key, List<String> required) {
    return context.copyWithRequired(required != null && required.contains(key));
  }

  private void compareAdditionalProperties(
      RecursiveSchemaSet refSet,
      Schema<?> leftSchema,
      Schema<?> rightSchema,
      DiffContext context,
      DeferredBuilder<Changed> builder) {
    Object left = leftSchema.getAdditionalProperties();
    Object right = rightSchema.getAdditionalProperties();
    if (left instanceof Schema || right instanceof Schema) {
      Schema<?> leftAdditionalSchema = left instanceof Schema ? (Schema<?>) left : null;
      Schema<?> rightAdditionalSchema = right instanceof Schema ? (Schema<?>) right : null;
      ChangedSchema apChangedSchema =
          new ChangedSchema()
              .setContext(context)
              .setOldSchema(leftAdditionalSchema)
              .setNewSchema(rightAdditionalSchema);
      if (left != null && right != null) {
        DeferredChanged<ChangedSchema> addPropChangedSchemaOP =
            openApiDiff
                .getSchemaDiff()
                .diff(
                    refSet,
                    leftAdditionalSchema,
                    rightAdditionalSchema,
                    context.copyWithRequired(false));
        builder
            .with(addPropChangedSchemaOP)
            .whenSet(
                optional -> {
                  ChangedSchema apc = optional.orElse(apChangedSchema);
                  isChanged(apc).ifPresent(changedSchema::setAddProp);
                });
      } else {
        isChanged(apChangedSchema).ifPresent(changedSchema::setAddProp);
      }
    }
  }

  public ChangedSchema getChangedSchema() {
    return this.changedSchema;
  }

  public OpenApiDiff getOpenApiDiff() {
    return this.openApiDiff;
  }
}
