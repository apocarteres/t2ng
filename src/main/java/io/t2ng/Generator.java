package io.t2ng;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.DirectoryFileFilter;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.String.format;
import static java.lang.String.join;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;
import static org.apache.commons.io.FileUtils.moveFile;

/**
 * Generates TypeScript contract, JavaScript implementation and Java beans
 * which well integrated across back-end and front-end layers and
 * compatible with Angular2 app
 */
public final class Generator {

    private static final Pattern THRIFT_ENUM_PATTERN = compile(
            "enum\\s+(\\w+)"
    );
    private static final Pattern THRIFT_TYPE_PATTERN = compile(
            "struct\\s+(\\w+)"
    );
    private static final Pattern THRIFT_EXCEPTION_PATTERN = compile(
            "exception\\s+(\\w+)"
    );
    private static final Pattern THRIFT_SERVICE_PATTERN = compile(
            "service\\s+(\\w+)"
    );
    private static final Pattern THRIFT_INCLUDE_PATTERN = compile(
            "include\\s+\"(\\w+)\\.thrift\""
    );
    private static final Pattern THRIFT_JS_NAMESPACE_PATTERN = compile(
            "namespace\\s+js\\s+([\\w\\._]+)"
    );

    public static void main(String[] args) throws Exception {
        String projectName = resolveProjectName(args);
        String tempDir = Files.createTempDirectory(projectName).toFile().getAbsolutePath();
        String inputDir = resolveInputDir(args);
        String generatedSourceDir = resolveGeneratedSourceDir(args);
        boolean mergeJsFiles = resolveMergeJsFilesOption(args);
        initFsTree(generatedSourceDir, tempDir);
        for (File thriftFile : listThriftFiles(inputDir)) {
            List<String> thriftContent = readFile(thriftFile);
            String thriftFilePath = thriftFile.getAbsolutePath();
            String jsNs = extractJsNamespace(thriftContent, thriftFilePath);
            String outputPath = findOrCreateDirectory(jsNs, thriftFilePath, tempDir);
            List<String> includeDirs = includeDirs(inputDir);
            Set<String> includedNamespaces = buildIncludedNamespaces(includeDirs, thriftContent);
            compileThrift(
                    thriftFile,
                    includeDirs,
                    format("%s/java", generatedSourceDir),
                    "java:generated_annotations=undated,beans,hashcode"
            );
            compileThrift(thriftFile, includeDirs, outputPath, "js:ts");
            composeTypeScriptModule(thriftContent, includedNamespaces, jsNs, outputPath, includeDirs, projectName,
                    generatedSourceDir
            );
            composeJavaScriptModule(thriftContent, jsNs, outputPath, includeDirs, projectName, generatedSourceDir);
            listTypeScriptFiles(outputPath).forEach(File::delete);
            listJavaScriptFiles(outputPath).forEach(File::delete);
        }
        if (mergeJsFiles) {
            Collection<File> files = listJavaScriptFiles(generatedSourceDir);
            StringBuilder builder = new StringBuilder();
            for (File file : files) {
                try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                    builder.append(join(format("%n"), reader.lines().collect(toList())));
                }
            }
            files.forEach(File::delete);
            try (Writer writer = new FileWriter(format("%s/js/all.js", generatedSourceDir))) {
                IOUtils.write(builder.toString(), writer);
            }
        }
    }

    private static boolean resolveMergeJsFilesOption(String[] args) {
        return Arrays.stream(args).filter(s -> s.equals("-m")).count() > 0;
    }

    private static String resolveProjectName(String[] args) {
        Optional<String> maybeProjectName = readArgument("-p", args);
        if (maybeProjectName.isPresent()) {
            return maybeProjectName.get();
        }
        throw new RuntimeException("project name must be specified. please use -p key");
    }

    private static String resolveInputDir(String[] args) {
        Optional<String> maybeProjectName = readArgument("-i", args);
        if (maybeProjectName.isPresent()) {
            return maybeProjectName.get();
        }
        throw new RuntimeException("input directory must be specified. please use -i key");
    }

    private static String resolveGeneratedSourceDir(String[] args) {
        Optional<String> maybeProjectName = readArgument("-s", args);
        if (maybeProjectName.isPresent()) {
            return maybeProjectName.get();
        }
        throw new RuntimeException("generated source directory must be specified. please use -s key");
    }

    private static Optional<String> readArgument(
            String key,
            String[] args
    ) {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (key.equals(arg)) {
                if (i + 1 <= args.length - 1) {
                    String value = args[i + 1];
                    if (value.startsWith("-")) {
                        throw new IllegalArgumentException(format("missed value for parameter %s", key));
                    }
                    return Optional.of(value);
                } else {
                    throw new IllegalArgumentException(format("missed value for parameter %s", key));
                }
            }
        }
        return Optional.empty();
    }

    private static Set<String> buildIncludedNamespaces(
            List<String> includeDirs,
            List<String> strings
    ) {
        return capture(strings, THRIFT_INCLUDE_PATTERN).stream().map(include -> extractJsNamespace(
                readFile(resolveFile(includeDirs, include)),
                include
        )).collect(Collectors.toSet());
    }

    private static File resolveFile(
            List<String> includeDirs,
            String include
    ) {
        for (String dir : includeDirs) {
            File file = new File(format(
                    "%s/%s.thrift",
                    dir,
                    include
            ));
            if (file.exists()) {
                return file;
            }
        }
        throw new RuntimeException(format("unable to resolve include file %s", include));
    }

    private static void composeJavaScriptModule(
            List<String> thriftContent,
            String jsNs,
            String outputPath,
            List<String> includeDirs,
            String projectName,
            String generatedSourceDir
    ) throws IOException {
        String jsTempFile = format(
                "%s/%s.js.tmp",
                outputPath,
                jsNs
        );
        String jsText = joinLines(listJavaScriptFiles(outputPath).stream()
                .flatMap(f -> readFile(f).stream())
                .collect(toList()));
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(jsTempFile))) {
            writer.write(joinLines(composeJavaScriptImports(thriftContent, jsNs, includeDirs, projectName)));
            writer.newLine();
            writer.write(joinLines(adaptJavaScriptFile(thriftContent, jsText, jsNs)));
            writer.newLine();
        }
        String jsModuleName = jsTempFile.replace(".tmp", "");
        if (!new File(jsTempFile).renameTo(new File(jsModuleName))) {
            throw new RuntimeException(
                    format("can't build module JavaScript module %s", jsModuleName)
            );
        }
        moveFile(
                new File(jsModuleName),
                new File(
                        format(
                                "%s/%s",
                                jsGeneratedSourceDir(generatedSourceDir),
                                new File(jsModuleName).getName()
                        )
                )
        );
    }

    private static List<String> adaptJavaScriptFile(List<String> thriftContent, String jsText, String jsNs) {
        Set<String> enums = capture(thriftContent, THRIFT_ENUM_PATTERN);
        Set<String> types = capture(thriftContent, THRIFT_TYPE_PATTERN);
        Set<String> services = capture(thriftContent, THRIFT_SERVICE_PATTERN);
        Set<String> exceptions = capture(thriftContent, THRIFT_EXCEPTION_PATTERN);
        List<String> transformed = new ArrayList<>();
        for (String e : enums) {
            Pattern pattern = compile(format("%s\\.%s\\s+=\\s+\\{(.*?)\\};", jsNs, e), Pattern.DOTALL);
            Matcher matcher = pattern.matcher(jsText);
            if (matcher.find()) {
                String enumBody = matcher.group(1);
                transformed.add(format("exports.%s = { %s }", e, enumBody));
            }
        }
        types.addAll(exceptions);
        for (String t : types) {
            Pattern ctorPattern = compile(format("%s\\.%s\\s+=\\s+function(.*?)\\};", jsNs, t), Pattern.DOTALL);
            Matcher ctorMatcher = ctorPattern.matcher(jsText);
            if (ctorMatcher.find()) {
                String functionBody = ctorMatcher.group(1);
                transformed.add(format("exports.%s = (function () { function %s %s}", t, t, functionBody));
                transformed.add(format("%s.prototype = {};", t));
                if (exceptions.contains(t)) {
                    transformed.add(format("Thrift.inherits(%s, Thrift.TException);", t));
                    transformed.add(format("%s.prototype.name = '%s';", t, t));
                }
                Pattern prototypePattern = compile(
                        format("%s\\.(%s.prototype\\.(?:read|write).*?)\\};", jsNs, t),
                        Pattern.DOTALL
                );
                Matcher prototypeMatcher = prototypePattern.matcher(jsText.replaceAll("= \\{\\};", "= {}"));
                while (prototypeMatcher.find()) {
                    transformed.add(format("%s};", prototypeMatcher.group(1)));
                }
                transformed.add(format("return %s;", t));
                transformed.add(format("})();%n"));
            }
        }
        for (String s : services) {
            Pattern ctorPattern = compile(format("%s\\.%sClient\\s+=\\s+function(.*?)\\};", jsNs, s), Pattern.DOTALL);
            Matcher ctorMatcher = ctorPattern.matcher(jsText);
            if (ctorMatcher.find()) {
                String functionBody = ctorMatcher.group(1);
                transformed.add(format("exports.%sClient = (function () { ", s));
                transformed.add(format(
                        "function %sClient %s}",
                        s,
                        functionBody
                ));
                Pattern servicePattern = compile(
                        format("\\W+(\\s+%s\\.%s_.*?\\s+=\\s+function.*?)\\};", jsNs, s),
                        Pattern.DOTALL
                );
                Matcher servicePatterMatcher = servicePattern.matcher(jsText.replaceAll("= \\{\\};", "= {}"));
                while (servicePatterMatcher.find()) {
                    String group = servicePatterMatcher.group(1);
                    transformed.add(format("%s};", group));
                }
                Pattern prototypePattern = compile(
                        format("%s\\.(%sClient.prototype.*?)\\};", jsNs, s),
                        Pattern.DOTALL
                );
                Matcher prototypeMatcher = prototypePattern.matcher(jsText);
                while (prototypeMatcher.find()) {
                    transformed.add(format("%s};", prototypeMatcher.group(1)));
                }
                transformed.add(format("return %sClient;", s));
                transformed.add(format("})();%n"));
            }
        }
        return transformed;
    }

    private static Collection<String> composeJavaScriptImports(
            List<String> strings,
            String jsNs,
            List<String> includeDirs,
            String projectName
    ) {
        List<String> imports = capture(strings, THRIFT_INCLUDE_PATTERN).stream()
                .map(include ->
                        extractJsNamespace(readFile(resolveFile(includeDirs, include)), include)
                ).collect(Collectors.toList());
        imports.add(jsNs);
        return imports.stream().map(s -> format(
                "var %s = require ('%s/%s');",
                s,
                projectName,
                s
        )).collect(Collectors.toSet());
    }

    private static void composeTypeScriptModule(
            List<String> thriftContent,
            Set<String> includedNamespaces,
            String jsNs,
            String outputPath,
            List<String> includeDirs,
            String projectName,
            String generatedSourceDir
    ) throws IOException {
        String tsTempFile = format("%s/%s.d.ts.tmp", outputPath, jsNs);
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(tsTempFile))) {
            writer.write(makeTypeScriptModuleDeclaration(jsNs, projectName));
            writer.newLine();
            writer.write(importThriftAsModule());
            writer.newLine();
            writer.write(joinLines(composeTypeScriptImports(thriftContent, includeDirs, projectName)));
            writer.newLine();
            writer.write(joinLines(
                    listTypeScriptFiles(outputPath).stream()
                            .flatMap(f -> adaptTypeScriptFile(
                                    thriftContent,
                                    includedNamespaces,
                                    f,
                                    jsNs
                            ).stream())
                            .collect(toList())
            ));
            writer.newLine();
            writer.write("}");
        }
        String tsModuleName = tsTempFile.replace(".tmp", "");
        if (!new File(tsTempFile).renameTo(new File(tsModuleName))) {
            throw new RuntimeException(
                    format("can't build module TypeScript module %s", tsModuleName)
            );
        }
        moveFile(
                new File(tsModuleName),
                new File(
                        format(
                                "%s/%s",
                                tsGeneratedSourceDir(generatedSourceDir),
                                new File(tsModuleName).getName()
                        )
                )
        );
    }

    private static List<String> adaptTypeScriptFile(
            List<String> strings,
            Set<String> includedNamespaces,
            File f,
            String jsNs
    ) {
        Set<String> enums = capture(strings, THRIFT_ENUM_PATTERN);
        Set<String> types = capture(strings, THRIFT_TYPE_PATTERN);
        Set<String> services = capture(strings, THRIFT_SERVICE_PATTERN);
        Set<String> serviceClients = capture(
                strings,
                THRIFT_SERVICE_PATTERN
        ).stream().map(s -> format("%sClient", s)).collect(
                Collectors.toSet());
        Set<String> classes = new HashSet<String>() {{
            addAll(types);
            addAll(services);
            addAll(serviceClients);
        }};
        List<String> adapted = new ArrayList<>();
        for (String s : readFile(f)) {
            if (s.isEmpty()) {
                continue;
            }
            if (s.contains("declare module")) {
                continue;
            }
            if (s.startsWith("//")) {
                continue;
            }
            for (String e : enums) {
                s = s.replaceFirst(format("^\\s+enum\\s+%s\\s+\\{", e), format("export enum %s {", e));
            }
            for (String e : classes) {
                s = s.replaceFirst(format("^\\s+class\\s+%s\\s+\\{", e), format("export class %s {", e));
            }
            s = s.replaceAll(format("%s\\.", jsNs), "");
            s = s.replaceAll("^\\s+", "");
            s = s.replaceAll("/\\*.+?\\*/","");/*comments when export from other Thrift file*/
            for (String include : includedNamespaces) {
                s = s.replaceAll(format("[^\"]%s\\.", include), "");
            }
            adapted.add(s);
        }
        if (!adapted.isEmpty()) {
            adapted.remove(adapted.size() - 1);
        }
        return adapted;
    }


    private static List<String> composeTypeScriptImports(
            List<String> strings,
            List<String> includeDirs,
            String projectName
    ) {
        Set<String> includes = capture(strings, THRIFT_INCLUDE_PATTERN);
        List<String> imports = new ArrayList<>();
        for (String include : includes) {
            Set<String> symbols = new HashSet<>();
            Pattern pattern = compile(format("[^\\.\"]%s\\.(\\w+)", include));
            for (String string : strings) {
                Matcher matcher = pattern.matcher(string);
                while (matcher.find()) {
                    symbols.add(matcher.group(1));
                }
            }
            imports.addAll(symbols.stream().map(symbol -> format(
                    "import { %s } from '%s/%s';",
                    symbol,
                    projectName,
                    extractJsNamespace(readFile(resolveFile(includeDirs, include)), include)
            )).collect(Collectors.toList()));
        }
        return imports;
    }

    private static String extractJsNamespace(List<String> strings, String fileName) {
        Set<String> capture = capture(strings, THRIFT_JS_NAMESPACE_PATTERN);
        if (capture.size() != 1) {
            throw new RuntimeException(
                    format(
                            "incorrect or missing JavaScript namespace in Thrift file %s",
                            fileName
                    )
            );
        }
        return capture.iterator().next();
    }

    private static String joinLines(Collection<String> strings) {
        return join(format("%n"), strings);
    }

    private static Collection<File> listThriftFiles(String inputDir) {
        return FileUtils.listFiles(
                new File(inputDir),
                null,
                true
        );
    }

    private static Collection<File> listTypeScriptFiles(String path) {
        return FileUtils.listFiles(
                new File(path),
                new String[]{"d.ts"},
                true
        );
    }

    private static Collection<File> listJavaScriptFiles(String path) {
        return FileUtils.listFiles(
                new File(path),
                new String[]{"js"},
                true
        );
    }

    private static List<String> readFile(File file) {
        try {
            return FileUtils.readLines(
                    file,
                    Charset.forName("UTF-8")
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void compileThrift(File file, List<String> includeDirs, String outputPath, String options) throws
            IOException, InterruptedException {
        String thriftFilePath = file.getAbsolutePath();
        List<String> includes = new ArrayList<>();
        for (String includeDir : includeDirs) {
            includes.add("-I");
            includes.add(includeDir);
        }
        if (new ProcessBuilder(new ArrayList<String>() {{
            add("thrift");
            addAll(includes);
            add("-out");
            add(outputPath);
            add("--gen");
            add(options);
            add(thriftFilePath);
        }}).start().waitFor() != 0) {
            throw new RuntimeException(format("can't compile Thrift file %s for Java", thriftFilePath));
        }
    }

    private static String findOrCreateDirectory(String jsNs, String thriftFilePath, String tempDir) {
        String outputPath = format("%s/%s", tempDir, jsNs);
        if (!new File(outputPath).exists()) {
            if (!new File(outputPath).mkdirs()) {
                throw new RuntimeException(
                        format(
                                "can't create directory %s for %s",
                                outputPath,
                                thriftFilePath
                        )
                );
            }
        }
        return outputPath;
    }

    private static void initFsTree(String generatedSourceDir, String tempDir) {
        if (FileUtils.deleteQuietly(new File(generatedSourceDir))) {
            System.out.println(format("wiped output generated source directory %s", generatedSourceDir));
        }
        if (FileUtils.deleteQuietly(new File(tempDir))) {
            System.out.println(format("wiped temp directory %s", tempDir));
        }
        if (!new File(tempDir).mkdirs()) {
            throw new RuntimeException(format("can't init temp directory at %s", tempDir));
        }
        if (!new File(generatedSourceDir).mkdirs()) {
            throw new RuntimeException(format(
                    "can't init generated source directory at %s",
                    generatedSourceDir
            ));
        }
        for (String dir : new String[]{
                javaGeneratedSourceDir(generatedSourceDir),
                jsGeneratedSourceDir(generatedSourceDir),
                tsGeneratedSourceDir(generatedSourceDir)}) {
            if (!new File(dir).mkdirs()) {
                throw new RuntimeException(format("can't init generated source directory at %s", dir));
            }
        }
    }

    private static List<String> includeDirs(String root) {
        List<String> result = new ArrayList<>();
        result.add(root);
        for (String s : new File(root).list(DirectoryFileFilter.DIRECTORY)) {
            result.addAll(includeDirs(format("%s/%s", root, s)));
        }
        return result;
    }

    private static Set<String> capture(Collection<String> input, Pattern pattern) {
        return input.stream().flatMap(s -> capture(s, pattern).stream()).collect(toSet());
    }

    private static List<String> capture(String input, Pattern pattern) {
        Matcher matcher = pattern.matcher(input);
        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    private static String makeTypeScriptModuleDeclaration(String jsNs, String projectName) {
        return format("declare module '%s/%s' {", projectName, jsNs);
    }

    private static String javaGeneratedSourceDir(String generatedSourceDir) {
        return format("%s/java", generatedSourceDir);
    }

    private static String jsGeneratedSourceDir(String generatedSourceDir) {
        return format("%s/js", generatedSourceDir);
    }

    private static String tsGeneratedSourceDir(String generatedSourceDir) {
        return format("%s/ts", generatedSourceDir);
    }

    private static String importThriftAsModule() {
        return "import Thrift from 'thrift/Thrift';";
    }
}
