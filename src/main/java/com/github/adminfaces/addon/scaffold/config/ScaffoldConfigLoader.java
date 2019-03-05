package com.github.adminfaces.addon.scaffold.config;

import com.github.adminfaces.addon.scaffold.model.ComponentTypeEnum;
import static com.github.adminfaces.addon.scaffold.model.ComponentTypeEnum.*;
import com.github.adminfaces.addon.scaffold.model.EntityConfig;
import com.github.adminfaces.addon.scaffold.model.FieldConfig;
import com.github.adminfaces.addon.scaffold.model.GlobalConfig;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;

import javax.persistence.Basic;
import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Id;
import javax.persistence.Temporal;
import javax.persistence.Transient;

import org.jboss.forge.addon.projects.Project;
import org.jboss.forge.addon.projects.facets.ResourcesFacet;
import org.jboss.forge.addon.resource.DirectoryResource;
import org.jboss.forge.addon.resource.FileResource;
import org.jboss.forge.roaster.model.Type;
import org.jboss.forge.roaster.model.source.AnnotationSource;
import org.jboss.forge.roaster.model.source.FieldSource;
import org.jboss.forge.roaster.model.source.JavaClassSource;
import org.yaml.snakeyaml.Yaml;

import com.github.adminfaces.addon.util.AdminScaffoldUtils;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.yaml.snakeyaml.DumperOptions;

public class ScaffoldConfigLoader {

    public static final DumperOptions YML_DUMP_OPTIONS;

    static {
        YML_DUMP_OPTIONS = new DumperOptions();
        YML_DUMP_OPTIONS.setExplicitEnd(false);
        YML_DUMP_OPTIONS.setSplitLines(true);
        YML_DUMP_OPTIONS.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        YML_DUMP_OPTIONS.setDefaultScalarStyle(DumperOptions.ScalarStyle.PLAIN);
    }

    private static GlobalConfig globalConfig;

    public static GlobalConfig loadGlobalConfig(Project project) {
        if (globalConfig == null) {
            DirectoryResource scaffoldDir = project.getFacet(ResourcesFacet.class).getResourceDirectory()
                .getChildDirectory("scaffold");
            FileResource<?> globalConfigFile = (FileResource<?>) scaffoldDir.getChild("global-config.yml");
            try (InputStream entityConfigStream = globalConfigFile.getResourceInputStream()) {
                globalConfig = new Yaml().loadAs(entityConfigStream, GlobalConfig.class);
            } catch (IOException e) {
                throw new RuntimeException("Could not load  scaffold config from:" + globalConfigFile.getFullyQualifiedName(), e);
            }
        }
        return globalConfig;
    }

    public static EntityConfig createOrLoadEntityConfig(JavaClassSource entity, Project project) {
        DirectoryResource scaffoldDir = project.getFacet(ResourcesFacet.class).getResourceDirectory()
            .getChildDirectory("scaffold");
        FileResource<?> entityConfigFile = (FileResource<?>) scaffoldDir.getChild(entity.getName().toLowerCase() + ".yml");
        EntityConfig entityConfig = null;
        if (!entityConfigFile.exists()) {
            entityConfig = createEntityConfig(entity, entityConfigFile, project);
        } else {
            entityConfig = loadEntityConfig(entityConfigFile);
        }

        return entityConfig;
    }

    private static EntityConfig loadEntityConfig(FileResource<?> entityConfigFile) {
        try (InputStream entityConfigStream = entityConfigFile.getResourceInputStream()) {
            return new Yaml().loadAs(entityConfigStream, EntityConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Could not load entity config from:" + entityConfigFile.getFullyQualifiedName(), e);
        }
    }

    private static EntityConfig createEntityConfig(JavaClassSource entity, FileResource<?> entityConfigFile, Project project) {
        GlobalConfig globalConfig = loadGlobalConfig(project);
        EntityConfig entityConfig = new EntityConfig();
        List<FieldSource<JavaClassSource>> entityFields = new ArrayList<>(entity.getFields());
        AdminScaffoldUtils.extractEmbeddedFields(entity).forEach(embeddedField -> {
            try {
                List<FieldSource<JavaClassSource>> fieldsFromEmbeddedField = AdminScaffoldUtils.getFieldsFromEmbeddedField(embeddedField, project);
                if (!fieldsFromEmbeddedField.isEmpty()) {
                    entityFields.addAll(fieldsFromEmbeddedField);
                }
            } catch (FileNotFoundException ex) {
                Logger.getLogger(ScaffoldConfigLoader.class.getName()).log(Level.SEVERE, null, ex);
            }
        });
        entityFields.stream()
            .filter(f -> f.hasAnnotation(Column.class) || AdminScaffoldUtils.hasAssociation(f)
            || f.hasAnnotation(Basic.class) || f.hasAnnotation(Transient.class) || f.hasAnnotation(Id.class)
            || f.hasAnnotation(EmbeddedId.class))
            .forEach(f -> {
                configFromField(f, globalConfig, entityConfig);
            });
        if (entityConfig.getDisplayField() == null) {
            entityConfig.setDisplayField("");//this means we'll use entity's toString() method to display entity on pages
        }
        entityConfig.setMenuIcon(globalConfig.getMenuIcon());
        entityConfig.setDatatableEditable(globalConfig.getDatatableEditable());
        entityConfig.setDatatableReflow(globalConfig.getDatatableReflow());
        entityConfigFile.setContents(new Yaml(YML_DUMP_OPTIONS).dump(entityConfig));
        return entityConfig;
    }

    private static void configFromField(FieldSource<JavaClassSource> f, GlobalConfig globalConfig1, EntityConfig entityConfig) {
        boolean required = AdminScaffoldUtils.resolveRequiredAttribute(f);
        Integer length = resolveLengthAttribute(f, globalConfig1.getInputSize());
        ComponentTypeEnum type = resolveComponentType(f, length, globalConfig1);
        entityConfig.getFields().add(new FieldConfig(f.getName(), required, false, length, type));
        if (entityConfig.getDisplayField() == null && type.equals(INPUT_TEXT) && required) { //by default displayField is the first non null inputText field
            entityConfig.setDisplayField(f.getName());
        }
    }

    private static Integer resolveLengthAttribute(FieldSource<JavaClassSource> field, Integer defaultValue) {
        AnnotationSource<JavaClassSource> columnAnnotation = field.getAnnotation(Column.class);
        Integer length = defaultValue;
        if (columnAnnotation != null && columnAnnotation.getStringValue("length") != null) {
            length = Integer.parseInt(columnAnnotation.getStringValue("length"));
        }
        return length;
    }

    private static ComponentTypeEnum resolveComponentType(FieldSource<JavaClassSource> field, Integer length, GlobalConfig globalConfig) {
        if (field.hasAnnotation(Temporal.class)) {
            return globalConfig.getDateComponentType();
        }
        if (AdminScaffoldUtils.hasToManyAssociation(field)) {
            return globalConfig.getToManyComponentType();
        }

        if (AdminScaffoldUtils.hasToOneAssociation(field)) {
            return globalConfig.getToOneComponentType();
        }
        Type<JavaClassSource> type = field.getType();
        if (type.isType(String.class)) {
            if (field.getName().toLowerCase().contains("password")) {
                return PASSWORD;
            }
            if (length > globalConfig.getInputSize()) {
                return TEXT_AREA;
            } else {
                return INPUT_TEXT;
            }
        }
        if (type.isType(Long.class) || type.isType(Integer.class) || type.isType(Double.class) || type.isType(Short.class) || type.isType(BigDecimal.class)
            || type.isType("long") || type.isType("int") || type.isType("double") || type.isType("short")) {
            return INPUT_NUMBER;
        }
        if (type.isType(Boolean.class) || type.isType("boolean")) {
            return INPUT_SWITCH;
        }
        //TODO inspect other fields types

        return INPUT_TEXT;
    }

}
