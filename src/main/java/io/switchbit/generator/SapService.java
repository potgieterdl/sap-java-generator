package io.switchbit.generator;

import com.sap.conn.jco.*;
import com.sap.conn.jco.ext.DataProviderException;
import com.sap.conn.jco.ext.DestinationDataEventListener;
import com.sap.conn.jco.ext.DestinationDataProvider;
import com.squareup.javapoet.*;
import io.switchbit.hibersap.PropertyDestinationDataProvider;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang3.StringUtils;
import org.hibersap.annotations.*;
import org.hibersap.bapi.BapiRet2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.lang.model.element.Modifier;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;

/**
 * Created by derick on 2016/02/07.
 */
@Service
public class SapService {
    private final static Logger log = LoggerFactory.getLogger(SapService.class);

    @Autowired
    private SapSettings s3Settings;
    @Autowired
    private PropertyDestinationDataProvider propertyDestinationDataProvider;
    private Map<String,String> subClassTypes;

    private static String DESTINATION_NAME = "DP_ABAP_AS_WITH_POOL";
    private static JCoDestination destination;
    Properties connectProperties = new Properties();

    @PostConstruct
    public void init() {
        log.debug("Loading properties for sap host : " + s3Settings.getAshost());
        Properties connectProperties = new Properties();
        connectProperties.setProperty(DestinationDataProvider.JCO_ASHOST, s3Settings.getAshost());
        connectProperties.setProperty(DestinationDataProvider.JCO_SYSNR, s3Settings.getSysnr());
        connectProperties.setProperty(DestinationDataProvider.JCO_CLIENT, s3Settings.getClient());
        connectProperties.setProperty(DestinationDataProvider.JCO_USER, s3Settings.getUser());
        connectProperties.setProperty(DestinationDataProvider.JCO_PASSWD, s3Settings.getPasswd());
        connectProperties.setProperty(DestinationDataProvider.JCO_LANG, s3Settings.getLang());
        connectProperties.setProperty(DestinationDataProvider.JCO_POOL_CAPACITY, s3Settings.getPoolCapacity());
        connectProperties.setProperty(DestinationDataProvider.JCO_PEAK_LIMIT, s3Settings.getPeakLimit());

        try {
            log.debug("Registering new custom sap data provider: " + PropertyDestinationDataProvider.class);
            propertyDestinationDataProvider.addDestination(DESTINATION_NAME, connectProperties);
            com.sap.conn.jco.ext.Environment.registerDestinationDataProvider(propertyDestinationDataProvider);
        } catch (IllegalStateException providerAlreadyRegisteredException) {
            log.error(PropertyDestinationDataProvider.class + " already registered");
            throw new Error(providerAlreadyRegisteredException);
        }
        try {
            destination = JCoDestinationManager.getDestination(DESTINATION_NAME);
        } catch (JCoException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    static void createDestinationDataFile(String destinationName, Properties connectProperties) {
        File destCfg = new File(destinationName);
        try {
            FileOutputStream fos = new FileOutputStream(destCfg, false);
            connectProperties.store(fos, "for tests only !");
            fos.close();
        } catch (Exception e) {
            throw new RuntimeException("Unable to create the destination files", e);
        }
    }

    public String makeNamesPritty(String name){
        return StringUtils.remove(WordUtils.capitalizeFully(name,new char[]{'_'}), "_");
    }

    public String generateJavaSourceFromRFC(String bapiName) throws JCoException {
        subClassTypes = new HashMap<>();
        JCoFunction function = destination.getRepository().getFunction(bapiName);
        String packagePath = "za.co.xeon.external.sap.hibersap";
        String className = makeNamesPritty(bapiName.toUpperCase().startsWith("Z_GET") ? bapiName.substring(5) : bapiName) + "RFC";
        String packageWithParentName = packagePath + "." + className;

        log.debug("Generating java source for structure 2: \n{}", prettyFormat(function.toXML()));
        if (function == null) {
            throw new RuntimeException(bapiName + " not found in SAP.");
        }

        TypeSpec.Builder builder = TypeSpec.classBuilder(ClassName.get(packageWithParentName, className))
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(AnnotationSpec.builder(Bapi.class).addMember("value", "$S", bapiName).build());
        MethodSpec.Builder constructorBuilder = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PUBLIC);


        // =============================================================================================================
        //      IMPORT PARAMETERS
        // =============================================================================================================
        JCoParameterList importParameters = function.getImportParameterList();
        if (importParameters != null) {
            JCoParameterFieldIterator it = importParameters.getParameterFieldIterator();
            while (it.hasNextField()) {
                JCoField test = it.nextParameterField();
                String fieldName = makeNamesPritty(test.getName());
                String typeName = fieldName;
                fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);
                switch (test.getTypeAsString()){
                    case "TABLE":
                        ClassName newType = ClassName.get(packageWithParentName, typeName);
                        ClassName list = ClassName.get("java.util", "List");
                        builder.addField(FieldSpec.builder(ParameterizedTypeName.get(list, newType), fieldName)
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                            .addAnnotation(Import.class)
                            .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
                            .addJavadoc("$S", test.getDescription())
                            .build());
                        builder.addType(buildSubClass(typeName, test.getTable().getRecordFieldIterator()).build());
                        constructorBuilder
                            .addParameter(ParameterSpec.builder(ParameterizedTypeName.get(list, newType), fieldName).build())
                            .addStatement("this.$N = $N", fieldName, fieldName);
                        break;
                    case "STRUCTURE":
                        log.debug("Import parameter not yet implemented succesfully.");
                        builder.addField(FieldSpec.builder(TypeVariableName.get(typeName), fieldName)
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                            .addAnnotation(Import.class)
                            .addAnnotation(AnnotationSpec.builder(Parameter.class)
                                .addMember("value", "$S", test.getName())
                                .addMember("type", "$T.$L", ParameterType.class, ParameterType.STRUCTURE.name())
                                .build())
                            .addJavadoc("$S", test.getDescription())
                            .build());
                        builder.addType(buildSubClass(typeName, test.getStructure().getRecordFieldIterator()).build());
                        break;
                    default:
                        builder.addField(FieldSpec.builder(String.class, fieldName)
                            .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
                            .addAnnotation(Import.class)
                            .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
                            .addJavadoc("$S", test.getDescription())
                            .build());
                        constructorBuilder
                            .addParameter(String.class, fieldName)
                            .addStatement("this.$N = $N", fieldName, fieldName)
                            .addJavadoc("@param $S - $S", fieldName, test.getDescription());
                        break;
                }
            }
        }

        // =============================================================================================================
        //      EXPORT PARAMETERS
        // =============================================================================================================
        JCoParameterList exportParameters = function.getExportParameterList();
        if (exportParameters != null) {
            JCoParameterFieldIterator it = exportParameters.getParameterFieldIterator();
            while (it.hasNextField()) {
                JCoField test = it.nextParameterField();
                String fieldName = makeNamesPritty(test.getName());
                String typeName = fieldName;
                fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);

                MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + typeName)
                    .addModifiers(Modifier.PUBLIC);

                if(test.getName().equals("RETURN") || test.getName().equals("EV_RETURN")){
                    builder.addField(FieldSpec.builder(BapiRet2.class, fieldName + "Type")
                        .addModifiers(Modifier.PRIVATE)
                        .addAnnotation(Export.class)
                        .addAnnotation(AnnotationSpec.builder(Parameter.class)
                            .addMember("value", "$S", test.getName())
                            .addMember("type", "$T.$L", ParameterType.class, ParameterType.STRUCTURE.name())
                            .build())
                        .addJavadoc("$S", test.getDescription())
                        .addJavadoc("@return $S - $S", typeName, test.getDescription())
                        .build());
                    getter.returns(ClassName.get(BapiRet2.class))
                        .addStatement("return $T", TypeVariableName.get(fieldName + "Type"));
                }else {
                    switch (test.getTypeAsString()) {
                        case "TABLE":
                            ClassName newType = ClassName.get(packageWithParentName, typeName);
                            ClassName list = ClassName.get("java.util", "List");
                            builder.addField(FieldSpec.builder(ParameterizedTypeName.get(list, newType), fieldName)
                                .addModifiers(Modifier.PRIVATE)
                                .addAnnotation(Export.class)
                                .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
                                .addJavadoc("$S", test.getDescription())
                                .build());

                            builder.addType(buildSubClass(typeName, test.getTable().getRecordFieldIterator()).build());
                            getter.returns(ParameterizedTypeName.get(list, newType))
                                .addStatement("return $T", TypeVariableName.get(fieldName));
                            break;
                        case "STRUCTURE":
                            builder.addField(FieldSpec.builder(TypeVariableName.get(typeName), fieldName)
                                .addModifiers(Modifier.PRIVATE)
                                .addAnnotation(AnnotationSpec.builder(Parameter.class)
                                    .addMember("value", "$S", test.getName())
                                    .addMember("type", "$T.$L", ParameterType.class, ParameterType.STRUCTURE.name())
                                    .build())
                                .addAnnotation(Export.class)
                                .addJavadoc("$S", test.getDescription())
                                .build());

                            builder.addType(buildSubClass(typeName, test.getStructure().getRecordFieldIterator()).build());
                            getter.returns(TypeVariableName.get(typeName))
                                .addStatement("return $T", TypeVariableName.get(fieldName));
                            break;
                        default:
                            builder.addField(FieldSpec.builder(String.class, fieldName)
                                .addModifiers(Modifier.PRIVATE)
                                .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
                                .addAnnotation(Export.class)
                                .addJavadoc("$S", test.getDescription())
                                .build());
                            getter.returns(String.class)
                                .addStatement("return $T", TypeVariableName.get(fieldName));
                            break;
                    }
                }
                builder.addMethod(getter.build());
            }
        }

        // =============================================================================================================
        //      TABLE PARAMETERS
        // =============================================================================================================
        JCoParameterList tableParameters = function.getTableParameterList();
        if (tableParameters != null) {
            JCoParameterFieldIterator it = tableParameters.getParameterFieldIterator();
            while (it.hasNextField()) {
                JCoField test = it.nextParameterField();
                String fieldName = makeNamesPritty(test.getName());
                String typeName = fieldName;
                fieldName = Character.toLowerCase(fieldName.charAt(0)) + fieldName.substring(1);

                MethodSpec.Builder getter = MethodSpec.methodBuilder("get" + typeName)
                    .addModifiers(Modifier.PUBLIC);

                if(test.getName().equals("RETURN") || test.getName().equals("EV_RETURN")){
                    builder.addField(FieldSpec.builder(ParameterizedTypeName.get(List.class, BapiRet2.class), fieldName + "Type")
                        .addModifiers(Modifier.PRIVATE)
                        .addAnnotation(Table.class)
                        .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
                        .addJavadoc("$S", test.getDescription())
                        .addJavadoc("@return $S - $S", typeName, test.getDescription())
                        .build());
                    getter.returns(ParameterizedTypeName.get(ClassName.get("java.util", "List"), ClassName.get(BapiRet2.class)))
                        .addStatement("return $T", TypeVariableName.get(fieldName + "Type"));
                }else {
                    switch (test.getTypeAsString()) {
                        case "TABLE":
                            ClassName newType = ClassName.get(packageWithParentName, typeName);
                            ClassName list = ClassName.get("java.util", "List");
                            builder.addField(FieldSpec.builder(ParameterizedTypeName.get(list, newType), fieldName)
                                .addModifiers(Modifier.PRIVATE)
                                .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
                                .addAnnotation(Table.class)
                                .addJavadoc("$S", test.getDescription())
                                .build());

                            getter.returns(ParameterizedTypeName.get(list, newType))
                                .addStatement("return $T", TypeVariableName.get(fieldName));
                            builder.addType(buildSubClass(typeName, test.getTable().getRecordFieldIterator()).build());
                            break;
//                        case "STRUCTURE":
//                            builder.addField(FieldSpec.builder(String.class, fieldName)
//                                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
//                                .addAnnotation(AnnotationSpec.builder(Parameter.class)
//                                    .addMember("value", "$S", test.getName())
//                                    .addMember("type", "$T.$L", ParameterType.class, ParameterType.STRUCTURE.name())
//                                    .build())
//                                .addAnnotation(Table.class)
//                                .addJavadoc("$S", test.getDescription())
//                                .build());
//                            break;
                        default:
                            throw new RuntimeException("We did not expect this....why would table types return anything other than a table type???? WTF aaaaaa....in fact there isnt enough coffee in the world to try and comprehend how this came to be...FU SAP");
//                            builder.addField(FieldSpec.builder(String.class, fieldName)
//                                .addModifiers(Modifier.PRIVATE, Modifier.FINAL)
//                                .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", test.getName()).build())
//                                .addAnnotation(Table.class)
//                                .addJavadoc("$S", test.getDescription())
//                                .build());
//                            break;
                    }
                }
                builder.addMethod(getter.build());
            }
        }

        builder.addMethod(constructorBuilder.build());
        TypeSpec helloWorld = builder.build();

        JavaFile javaFile = JavaFile.builder(packagePath, helloWorld)
            .build();

//        log.debug("Class file for RFC auto generation: \n{}", javaFile.toString());

        return javaFile.toString();
    }

    public TypeSpec.Builder buildSubClass(String className, JCoRecordFieldIterator recordFieldIterator){
        if(subClassTypes.containsKey(className)){
            log.debug(className + " already mapped to subclass");
            return null;
        }else {
            TypeSpec.Builder subClassBuilder = TypeSpec.classBuilder(className)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(BapiStructure.class);
            MethodSpec.Builder subConstructorBuilder = MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC);

            while (recordFieldIterator.hasNextField()) {
                JCoField jCoRecordField = recordFieldIterator.nextField();
                String subFieldName = makeNamesPritty(jCoRecordField.getName());
                String subTypeName = subFieldName;
                subFieldName = Character.toLowerCase(subFieldName.charAt(0)) + subFieldName.substring(1);

                subClassBuilder.addField(FieldSpec.builder(TypeVariableName.get(jCoRecordField.getClassNameOfValue()), subFieldName)
                    .addModifiers(Modifier.PRIVATE)
                    .addAnnotation(AnnotationSpec.builder(Parameter.class).addMember("value", "$S", jCoRecordField.getName()).build())
                    .addJavadoc("$S", jCoRecordField.getDescription()).build());

                subConstructorBuilder
                    .addParameter(ParameterSpec.builder(TypeVariableName.get(jCoRecordField.getClassNameOfValue()), subFieldName).build())
                    .addStatement("this.$N = $N", subFieldName, subFieldName);


                subClassBuilder.addMethod(MethodSpec.methodBuilder("get" + subTypeName)
                    .addModifiers(Modifier.PUBLIC)
                    .returns(TypeVariableName.get(jCoRecordField.getClassNameOfValue()))
                    .addJavadoc("@return $S - $S", subTypeName, jCoRecordField.getDescription())
                    .addStatement("return $T", TypeVariableName.get(subFieldName)).build());
            }
            subClassTypes.put(className,className);
            subClassBuilder.addMethod(subConstructorBuilder.build());
            subClassBuilder.addMethod(MethodSpec.constructorBuilder()
                .addModifiers(Modifier.PUBLIC).build());

            return subClassBuilder;
        }


    }

    public String diagnoseBapi(String bapiName, Map<String, String> params) throws JCoException {
        JCoFunction function = destination.getRepository().getFunction(bapiName);
        if (function == null) {
            throw new RuntimeException(bapiName + " not found in SAP.");
        }

        log.debug("RFC: \n{}", prettyFormat(function.toXML()));

        JCoParameterFieldIterator it;
        JCoParameterList importParameters = function.getImportParameterList();
        if (importParameters != null) {
            it = importParameters.getParameterFieldIterator();
            log.debug("=============================== IMPORT =========================================");
            while (it.hasNextField()) {
                log.debug("---------------------------------------------");
                JCoField test = it.nextParameterField();
                log.debug(test.getName());
                log.debug(test.getDescription());
                log.debug(test.getClassNameOfValue());
                log.debug(test.getTypeAsString());
                if (test.getTypeAsString().equals("TABLE")) {
                    printFieldDebugInfo(test.getTable().getRecordFieldIterator());
                }
                if (test.getTypeAsString().equals("STRUCTURE")) {
                    printFieldDebugInfo(test.getStructure().getRecordFieldIterator());
                }
                log.debug("---------------------------------------------");
            }
        }

        JCoParameterList exportParameters = function.getExportParameterList();
        if (exportParameters != null) {
            it = exportParameters.getParameterFieldIterator();
            log.debug("================================ EXPORT ========================================");
            while (it.hasNextField()) {
                log.debug("---------------------------------------------");
                JCoField test = it.nextParameterField();
                log.debug(test.getName());
                log.debug(test.getDescription());
                log.debug(test.getClassNameOfValue());
                log.debug(test.getTypeAsString());
                if (test.getTypeAsString().equals("TABLE")) {
                    printFieldDebugInfo(test.getTable().getRecordFieldIterator());
                }
                if (test.getTypeAsString().equals("STRUCTURE")) {
                    printFieldDebugInfo(test.getStructure().getRecordFieldIterator());
                }
            }

            log.debug("---------------------------------------------");
        }

        JCoParameterList tableParameters = function.getTableParameterList();
        if (tableParameters != null) {
            it = tableParameters.getParameterFieldIterator();
            log.debug("================================ TABLE ========================================");
            while (it.hasNextField()) {
                log.debug("---------------------------------------------");
                JCoField test = it.nextParameterField();
                log.debug("getName                          " + test.getName());
                log.debug("getDescription                   " + test.getDescription());
                log.debug("getClassNameOfValue              " + test.getClassNameOfValue());
                log.debug("getTypeAsString                  " + test.getTypeAsString());
//                log.debug("getRecordMetaData:toString       " + test.getRecordMetaData() == null ? "null" : test.getRecordMetaData().toString());
                if (test.getTypeAsString().equals("TABLE")) {
                    printFieldDebugInfo(test.getTable().getRecordFieldIterator());
                }
                if (test.getTypeAsString().equals("STRUCTURE")) {
                    printFieldDebugInfo(test.getStructure().getRecordFieldIterator());
                }
                log.debug("---------------------------------------------");
            }
        }

        // try to call function
        try {
//            function.getImportParameterList().setValue("IM_CUSTOMER", "213");

            params.forEach((key,value)->{
                log.debug("Item : " + key + " Count : " + value);
                function.getImportParameterList().setValue(key, value);
            });

            function.execute(destination);

        } catch (AbapException e) {
            System.out.println(e.toString());
        }

        return prettyFormat(function.toXML());
    }

    public void printFieldDebugInfo(JCoRecordFieldIterator recordFieldIterator) {
        printFieldDebugInfo(recordFieldIterator, false);
    }

    public void printFieldDebugInfo(JCoRecordFieldIterator recordFieldIterator, boolean includeValues) {
        while (recordFieldIterator.hasNextField()) {
            if(includeValues){
                log.debug("            " + formatColumnDetailsWithValues(recordFieldIterator.nextRecordField()));
            }else{
                log.debug("            " + formatColumnDetails(recordFieldIterator.nextRecordField()));
            }
        }
    }

    private static String formatColumnDetails(JCoRecordField field) {
        return String.format("%20s%10s%20s%60s", field.getName(), field.getTypeAsString(), field.getClassNameOfValue(), field.getDescription());
    }

    private static String formatColumnDetailsWithValues(JCoRecordField field) {
        return String.format("%20s%10s%20s%60s%60s", field.getName(), field.getTypeAsString(), field.getClassNameOfValue(), field.getDescription(), field.getValue());
    }


    public static String prettyFormat(String input, int indent) {
        try {
            Source xmlInput = new StreamSource(new StringReader(input));
            StringWriter stringWriter = new StringWriter();
            StreamResult xmlOutput = new StreamResult(stringWriter);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute("indent-number", indent);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(xmlInput, xmlOutput);
            return xmlOutput.getWriter().toString();
        } catch (Exception e) {
            throw new RuntimeException(e); // simple exception handling, please review it
        }
    }

    public static String prettyFormat(String input) {
        return prettyFormat(input, 2);
    }

}
