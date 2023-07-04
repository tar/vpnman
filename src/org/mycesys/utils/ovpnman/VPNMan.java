package org.mycesys.utils.ovpnman;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;


import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static java.nio.charset.StandardCharsets.*;

public class VPNMan {


    private static class OVPNServer {
        private final ExecutorService threadpool = Executors.newCachedThreadPool();
        private final String context;
        private final String staticDir;

        private final String address;
        private final int port;
        private final OVPNManager manager;

        public OVPNServer(String address, int port, String context, String staticDir, OVPNManager manager) {
            this.address = address;
            this.context = context;
            this.staticDir = staticDir;
            this.port = port;
            this.manager = manager;
        }

        private void directContext(HttpExchange exchange) {
            String responseData;
            int responseCode = 200;

            if ("GET".equals(exchange.getRequestMethod())) {
                var paths = exchange.getRequestURI().getPath().split("/");
                var hash = paths[paths.length - 1];
                var profile = manager.findByHash(hash);
                if (profile.isPresent()) {
                    try {
                        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"%s\"".formatted(profile.get().name() + ".ovpn"));
                        exchange.sendResponseHeaders(responseCode, Files.size(Paths.get(profile.get().profile())));
                        Files.copy(Paths.get(profile.get().profile()), exchange.getResponseBody());
                        return;
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                        responseCode = 500;
                        responseData = e.getMessage();
                    }
                } else {
                    responseCode = 404;
                    responseData = "Could not find profile by hash:" + hash;
                }
            } else {
                responseCode = 405;
                responseData = "Only GET method supported";
            }
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            try (OutputStream outputStream = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(responseCode, responseData.length());
                outputStream.write(responseData.getBytes());
                outputStream.flush();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }

        private void profilesContext(HttpExchange exchange) {
            String responseData;
            int responseCode = 200;
            try {
                responseData = switch (exchange.getRequestMethod()) {
                    case "GET" -> {
                        try {
                            yield JsonUtil.toJson(manager.getProfiles().toArray());
                        } catch (Exception e) {
                            System.out.println(e.getMessage());
                        }
                        yield "{}";
                    }
                    case "POST" -> {
                        Map<String, Object> request = JsonUtil.fromJSON(exchange.getRequestBody());
                        if (request.containsKey("name")) {
                            responseCode = 201;
                            yield JsonUtil.toJson(manager.createProfile((String) request.get("name")).get());
                        } else {
                            responseCode = 400;
                            yield "No name provided";
                        }
                    }
                    case "DELETE" -> {
                        var paths = exchange.getRequestURI().getPath().split("/");
                        var name = paths[paths.length - 1];
                        manager.deleteProfile(name);
                        yield name + " successfully deleted";
                    }
                    case "PUT" -> {
                        manager.updateProfiles();
                        yield "Successfully updated";
                    }
                    default -> {
                        responseCode = 405;
                        yield "Only GET, POST, PUT, DELETE methods supported";
                    }
                };
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            try (OutputStream outputStream = exchange.getResponseBody()) {
                exchange.sendResponseHeaders(responseCode, responseData.length());
                outputStream.write(responseData.getBytes());
                outputStream.flush();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }

        private void staticContext(HttpExchange exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                var responseData = "Only GET method supported";
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    exchange.sendResponseHeaders(405, responseData.length());
                    outputStream.write(responseData.getBytes());
                    outputStream.flush();
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            } else {
                var path = Paths.get(staticDir, exchange.getRequestURI().getPath().replaceFirst("/static/", ""));
                var extensionParts = path.getFileName().toString().split("\\.");
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    if (extensionParts.length > 1) {
                        var mimetype = switch (extensionParts[extensionParts.length - 1]) {
                            case "html" -> "text/html; charset=utf-8";
                            case "json" -> "application/json; charset=utf-8";
                            case "js" -> "text/javascript; charset=utf-8";
                            case "css" -> "text/css; charset=utf-8";
                            case "png" -> "image/png";
                            case "jpeg", "jpg", "jpe" -> "image/jpeg";
                            case "svg" -> "image/svg+xml";
                            case "ico" -> "image/x-icon";
                            case "gif" -> "image/gif";
                            case "ttf" -> "application/x-font-ttf";
                            case "woff" -> "application/font-woff";
                            default -> "application/octet-stream";
                        };
                        exchange.getResponseHeaders().set("Content-Type", mimetype);
                    } else {
                        exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment");
                    }
                    exchange.sendResponseHeaders(200, Files.size(path));
                    Files.copy(path, outputStream);
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            }
        }

        private void rootContext(HttpExchange exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                var responseData = "Only GET method supported";
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    exchange.sendResponseHeaders(405, responseData.length());
                    outputStream.write(responseData.getBytes());
                    outputStream.flush();
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            } else {
                var path = Paths.get(staticDir, "index.html");
                var extensionParts = path.getFileName().toString().split("\\.");
                try (OutputStream outputStream = exchange.getResponseBody()) {
                    if (Files.exists(path)) {
                        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                        exchange.sendResponseHeaders(200, Files.size(path));
                        Files.copy(path, outputStream);
                    } else {
                        exchange.sendResponseHeaders(404, -1);
                    }
                } catch (IOException e) {
                    System.err.println(e.getMessage());
                }
            }
        }

        public void start() {
            try {
                var server = HttpServer.create(new InetSocketAddress(address, port), 0);
                server.createContext("/", this::rootContext);
                server.createContext("/" + context + "/profiles", this::profilesContext);
                server.createContext("/static", this::staticContext);
                server.createContext("/direct", this::directContext);
                server.setExecutor(threadpool);
                server.start();
                System.out.println("Server started on: http://%s:%s/".formatted(address, String.valueOf(port)));
                System.out.println("Secret context:%s".formatted(context));
            } catch (IOException e) {
                System.err.println(e.getMessage());
            }
        }
    }

    private static class OVPNManager {


        private static enum EasyRSAAction {
            create,
            revoke;
        }

        private static final String DELIMETER = "_-_";
        private static final String EXTENSION = ".ovpn";

        record VpnProfile(String name, String profile, String hash) {
        }

        private String easyRSADir = "/etc/openvpn/server/easy-rsa";
        private String template;
        private String outputDir = "/home/crp/ovpn-files";

        private boolean isDryRun = false;

        private OVPNManager() {
        }

        public static Optional<OVPNManager> build(String easyRSADir, String template, String outputDir) {
            OVPNManager manager = new OVPNManager();
            Path easyRSABinary = Paths.get(easyRSADir, "easyrsa");
            if (!Files.exists(easyRSABinary) || !Files.isExecutable(easyRSABinary)) {
                System.out.println("Cannot find executable by path: `%s`. VPN management is not possible".formatted(easyRSABinary.toString()));
                return Optional.empty();
            }
            manager.easyRSADir = easyRSADir;
            manager.template = template;
            manager.outputDir = outputDir;
            return Optional.of(manager);
        }

        public static Optional<OVPNManager> build(String easyRSADir, String template, String outputDir, boolean isDev) {
            OVPNManager manager = new OVPNManager();
            Path easyRSABinary = Paths.get(easyRSADir, "easyrsa");
            if ((!Files.exists(easyRSABinary) || !Files.isExecutable(easyRSABinary)) && !isDev) {
                System.out.println("Cannot find executable by path: `%s`. VPN management is not possible".formatted(easyRSABinary.toString()));
                return Optional.empty();
            }
            manager.easyRSADir = easyRSADir;
            manager.template = template;
            manager.outputDir = outputDir;
            manager.isDryRun = isDev;
            return Optional.of(manager);
        }

        private Optional<VpnProfile> createProfile(String name) {
            var certPath = Paths.get(easyRSADir, "pki", "issued", name + ".crt");
            var keyPath = Paths.get(easyRSADir, "pki", "private", name + ".key");
            if (!Files.exists(certPath) || !Files.exists(keyPath)) {
                if (!isDryRun) {
                    runEasyRSAAction(name, EasyRSAAction.create);
                }
            }
            try {
                String cert = "";
                String key = "";
                if (!isDryRun) {
                    cert = Files.readString(certPath, UTF_8);
                    key = Files.readString(keyPath, UTF_8);

                } else {
                    //Random string provides different hashes
                    cert = UUID.randomUUID().toString();
                    key = UUID.randomUUID().toString();
                }
                String profileContent = this.template.replace("${CERT}", cert).replace("${KEY}", key);
                String hash = hashString(profileContent);
                Path profile = getOVPNFile(name, hash).orElseThrow();
                Files.writeString(profile, profileContent, StandardOpenOption.CREATE);
                return Optional.of(getProfileByPath(profile));
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
            return Optional.empty();
        }

        private void deleteProfile(String name) {
            var profile = findByName(name);
            if (profile.isEmpty()) {
                System.out.println("Could not find profile `%s` to delete.".formatted(name));
                return;
            }
            if (!isDryRun) {
                int status = runEasyRSAAction(name, EasyRSAAction.revoke);
            }
            //TODO process status and check index.txt
            try {
                Files.delete(Paths.get(profile.get().profile()));
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }

        private List<VpnProfile> getProfiles() {
            try (Stream<Path> list = Files.list(Paths.get(outputDir))) {
                return list.map(path -> getProfileByPath(path))
                        .sorted(Comparator.comparing(p -> p.name)).toList();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
            return Collections.emptyList();
        }

        public void updateProfiles() {
            try (var list = Files.list(Paths.get(outputDir))) {
                list.forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        System.out.println(e.getMessage());
                    }
                });
                try {
                    Files.readAllLines(Paths.get(easyRSADir, "pki", "index.txt"), UTF_8).stream()
                            .filter(line -> line.charAt(0) == 'V' && line.contains("/CN="))
                            .map(line -> line.substring(line.indexOf("/CN=") + 4))
                            //TODO remove hardcode for server certificate
                            .filter(name -> !"server".equals(name))
                            .forEach(name -> createProfile(name));
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }

        private int runEasyRSAAction(String name, EasyRSAAction action) {
            ProcessBuilder pb = switch (action) {
                case create -> new ProcessBuilder("./easyrsa", "build-client-full", name, "nopass");
                case revoke -> new ProcessBuilder("./easyrsa", "revoke", name);
            };
            pb.directory(Paths.get(easyRSADir).toFile());
            try {
                Process process = pb.start();
                Thread.sleep(200);
                process.getOutputStream().write("yes\n".getBytes(UTF_8));
                process.getOutputStream().flush();
                int exitCode = process.waitFor();
                System.out.println("Exit code for action: %s, code is %d".formatted(action, exitCode));
                return exitCode;
            } catch (IOException | InterruptedException e) {
                System.out.println(e.getMessage());
            }
            return -1;
        }


        private Optional<Path> getOVPNFile(String name, String hash) {
            if (name == null || name.isEmpty()) {
                return Optional.empty();
            }
            if (hash == null || hash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Paths.get(outputDir, getFileName(name, hash)));
        }

        private VpnProfile getProfileByPath(Path path) {
            var parts = path.getFileName().toString().split(DELIMETER);
            return new VpnProfile(parts[1].substring(0, parts[1].length() - 5), path.toAbsolutePath().toString(), parts[0]);
        }

        private Optional<VpnProfile> findByNameOrHash(String value, boolean isName) {
            if (value == null || value.isEmpty()) {
                return Optional.empty();
            }
            try (var list = Files.list(Paths.get(outputDir))) {
                return list.filter(path -> {
                    var parts = path.getFileName().toString().split(DELIMETER);
                    if (parts.length != 2) {
                        return false;
                    } else {
                        return isName ? parts[1].startsWith(value) : value.equals(parts[0]);
                    }
                }).map(path -> getProfileByPath(path)).findFirst();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
            return Optional.empty();
        }

        public Optional<VpnProfile> findByHash(String hash) {
            return findByNameOrHash(hash, false);
        }

        private Optional<VpnProfile> findByName(String name) {
            return findByNameOrHash(name, true);
        }

        private String getFileName(String name, String hash) {
            return hash + DELIMETER + name + EXTENSION;
        }

        public String hashString(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(s.getBytes(UTF_8));
                return HexFormat.of().formatHex(hash);
            } catch (NoSuchAlgorithmException e) {
                System.out.println(e.getMessage());
                throw new RuntimeException(e);
            }
        }

    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println(HELP_MESSSAGE);
            return;
        }

        Properties appConfig = parseArguments(args);
        var isDev = false;

        if (appConfig.containsKey(PARAM_HELP)) {
            System.out.println(HELP_MESSSAGE);
            return;
        }
        if (appConfig.containsKey(PARAM_API)) {
            System.out.println(API);
            return;
        }
        if (appConfig.containsKey(PARAM_DEFAULT_TEMPLATE)) {
            System.out.println(defaultTemplate);
            return;
        }

        if (appConfig.containsKey(PARAM_IS_DEV)) {
            System.out.println("Development mode: configs will not contain real keys. Easyrsa will not be executed.");
            isDev = true;
        }

        if (appConfig.containsKey(PARAM_CONFIG)) {
            String configPath = appConfig.getProperty(PARAM_CONFIG);
            try {
                appConfig = new Properties();
                appConfig.load(Files.newInputStream(Paths.get(configPath)));
            } catch (IOException e) {
                System.out.println("Failed to parse config file: " + e.getMessage());
                return;
            }
        }

        if (appConfig.containsKey(PARAM_EASYRSA)) {
            Path easyRSA = Paths.get(appConfig.getProperty(PARAM_EASYRSA));
            if (Files.isDirectory(easyRSA)) {
                appConfig.put(PARAM_EASYRSA, easyRSA.toAbsolutePath().toString());
            } else {
                System.out.println("Path for easyrsa `%s` is not a directory".formatted(easyRSA.toString()));
            }
        } else {
            System.out.println("--%s is required parameter. To learn more run with --help".formatted(PARAM_EASYRSA));
            return;
        }

        if (appConfig.containsKey(PARAM_OUTPUT)) {
            Path outputFiles = Paths.get(appConfig.getProperty(PARAM_OUTPUT));
            if (!Files.exists(outputFiles)) {
                try {
                    Files.createDirectory(outputFiles);
                } catch (IOException e) {
                    System.out.println("Cannot create dir for output files at %s. Will use default value. The reason:\n".formatted(outputFiles.toString()) + e.getMessage());
                    //TODO check is value the same as default
                    createDirAndSetProperty(PARAM_OUTPUT, Paths.get(appConfig.getProperty(PARAM_EASYRSA), PATH_CLIENT_PROFILES), appConfig);
                }
            } else if (!Files.isDirectory(outputFiles)) {
                System.out.println("Path for output files `%s` is not directory. Will use default value".formatted(outputFiles.toString()));
                //TODO check is value the same as default
                createDirAndSetProperty(PARAM_OUTPUT, Paths.get(appConfig.getProperty(PARAM_EASYRSA), PATH_CLIENT_PROFILES), appConfig);
            }
        } else {
            createDirAndSetProperty(PARAM_OUTPUT, Paths.get(appConfig.getProperty(PARAM_EASYRSA), PATH_CLIENT_PROFILES), appConfig);
        }

        String filledTemplate = "";
        if (appConfig.containsKey(PARAM_TEMPLATE)) {
            Path templatePath = Paths.get(appConfig.getProperty(PARAM_TEMPLATE));
            if (!Files.isRegularFile(templatePath)) {
                System.out.println("--%s parameter specified, but file doesn't exists".formatted(PARAM_TEMPLATE));
                return;
            } else {
                appConfig.put(PARAM_TEMPLATE, templatePath.toAbsolutePath().toString());
                try {
                    filledTemplate = Files.readString(templatePath);
                    if (!filledTemplate.contains("${CERT}") || !filledTemplate.contains("${KEY}")) {
                        System.out.println("Template file does not contain placeholders for client key (${KEY}) and cert(${CERT})");
                        return;
                    }
                } catch (IOException e) {
                    System.out.println("Unable to read template content. Reason\n" + e.getMessage());
                    return;
                }
            }
        } else {
            System.out.println("--%s parameter is not set. Using command-line parameters instead".formatted(PARAM_TEMPLATE));
            boolean necessaryTemplateParamsPresent = true;
            String ca = "";
            String tlsauth = "";
            if (!appConfig.containsKey(PARAM_VPNURL)) {
                if (!isDev) {
                    System.out.println("--%s parameter is required in this case".formatted(PARAM_VPNURL));
                    necessaryTemplateParamsPresent = false;
                } else {
                    appConfig.put(PARAM_VPNURL, "vpn.sample");
                }
            }
            if (!appConfig.containsKey(PARAM_VPNPORT)) {
                if (!isDev) {
                    System.out.println("--%s parameter is required in this case".formatted(PARAM_VPNPORT));
                    necessaryTemplateParamsPresent = false;
                } else {
                    appConfig.put(PARAM_VPNPORT, "1194");
                }
            }
            if (!appConfig.containsKey(PARAM_CA)) {
                if (!isDev) {
                    System.out.println("--%s parameter is required in this case".formatted(PARAM_CA));
                    necessaryTemplateParamsPresent = false;
                } else {
                    ca = "DRY-RUN MODE";
                }

            }
            if (!appConfig.containsKey(PARAM_TLSAUTH)) {
                if (!isDev) {
                    System.out.println("--%s parameter is required in this case".formatted(PARAM_TLSAUTH));
                    necessaryTemplateParamsPresent = false;
                } else {
                    tlsauth = "DRY-RUN MODE";
                }
            }
            if (!necessaryTemplateParamsPresent) {
                return;
            }
            if (!isDev) {
                try {
                    ca = Files.readString(Paths.get(appConfig.getProperty(PARAM_CA)));
                    tlsauth = Files.readString(Paths.get(appConfig.getProperty(PARAM_TLSAUTH)));
                } catch (IOException e) {
                    System.out.println("Unable to load CA certificate and TLSAUTH certificate. Reason:\n" + e.getMessage());
                    return;
                }
            }


            filledTemplate = defaultTemplate
                    .replace("${VPN_SERVER_IP}", appConfig.getProperty(PARAM_VPNURL))
                    .replace("${VPN_SERVER_PORT}", appConfig.getProperty(PARAM_VPNPORT))
                    .replace("${CA_CERTIFICATE}", ca)
                    .replace("${TLSAUTH_CERTIFICATE}", tlsauth);
        }

        if (!appConfig.containsKey(PARAM_URL)) {
            System.out.println("--%s is not specified. Will use default value".formatted(PARAM_URL));
            appConfig.put(PARAM_URL, DEFAULT_IP);
        }
        if (!appConfig.containsKey(PARAM_PORT)) {
            System.out.println("--%s is not specified. Will use default value".formatted(PARAM_PORT));
            appConfig.put(PARAM_PORT, DEFAULT_PORT.toString());
        }
        if (!appConfig.containsKey(PARAM_CONTEXT)) {
            var secretContext = UUID.randomUUID().toString();
            System.out.println("--%s is not specified. Will use generated one.".formatted(PARAM_CONTEXT));
            appConfig.put(PARAM_CONTEXT, secretContext);
        }
        //TODO save secret context to a file
        boolean isStaticDirOK = true;
        if (appConfig.containsKey(PARAM_STATIC)) {
            Path staticDir = Paths.get(appConfig.getProperty(PARAM_STATIC));
            if(!Files.exists(staticDir)){
                try {
                    Files.createDirectories(staticDir);
                } catch (IOException e) {
                    System.out.println(e.getMessage());
                }
            }
            if (!Files.isDirectory(staticDir)) {
                System.out.println("Path for static content `%s` is not directory. Will use default value".formatted(staticDir.toString()));
                isStaticDirOK = false;
            } else if (isEmpty(staticDir)) {
                downloadDefaultUI(staticDir);
            }
        } else {
            System.out.println("--%s is not specified. Will use default value".formatted(PARAM_STATIC));
            isStaticDirOK = false;
        }
        if (!isStaticDirOK) {
            createDirAndSetProperty(PARAM_STATIC, Paths.get(DEFAULT_STATIC), appConfig);
            if (isEmpty(Paths.get(DEFAULT_STATIC))) {
                downloadDefaultUI(Paths.get(DEFAULT_STATIC));
            }
        }

        Optional<OVPNManager> ovpnManager = OVPNManager.build(appConfig.getProperty(PARAM_EASYRSA), filledTemplate, appConfig.getProperty(PARAM_OUTPUT), isDev);
        if (ovpnManager.isEmpty()) {
            System.out.println("Unable to initialize app. Invalid parameters");
            return;
        }
        OVPNServer ovpnServer = new OVPNServer(appConfig.getProperty(PARAM_URL), Integer.parseInt(appConfig.getProperty(PARAM_PORT)),
                appConfig.getProperty(PARAM_CONTEXT), appConfig.getProperty(PARAM_STATIC), ovpnManager.get());
        ovpnServer.start();
        System.out.println();
    }

    private static void downloadDefaultUI(Path to) {

        String subfolder = "webapp/";
        try (InputStream in = new URI("https://github.com/tar/vpnman/archive/refs/heads/main.zip").toURL().openStream();
             ZipInputStream zipIn = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().substring(12).startsWith(subfolder)) {
                    Path targetPath = to.resolve(Paths.get(entry.getName()).getFileName());
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(zipIn, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zipIn.closeEntry();
            }
        } catch (IOException | URISyntaxException e) {
            System.out.println("Unable to download default webapp content. Reason\n" + e.getMessage());
        }
    }

    private static boolean isEmpty(Path path) {
        if (Files.isDirectory(path)) {
            try (Stream<Path> list = Files.list(path)) {
                return list.findFirst().isEmpty();
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        } else {
            try {
                return Files.size(path) == 0;
            } catch (IOException e) {
                System.out.println(e.getMessage());
            }
        }
        return false;
    }

    private static void createDirAndSetProperty(String property, Path path, Properties props) {
        try {
            Files.createDirectories(path);
            props.put(property, path.toAbsolutePath().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Properties parseArguments(String[] args) {
        Properties argMap = new Properties();
        for (String arg : args) {
            String[] splitArg = arg.split("=");
            if (splitArg.length > 1) {
                if (splitArg[0].length() > 2) {
                    String key = splitArg[0].substring(2);
                    argMap.put(key, splitArg[1]);
                }
            } else if (splitArg[0].equals("--%s".formatted(PARAM_HELP))) {
                argMap.put(PARAM_HELP, "");
            } else {
                var key = splitArg[0].substring(2);
                switch (key) {
                    case PARAM_HELP -> argMap.put(PARAM_HELP, "");
                    case PARAM_API -> argMap.put(PARAM_API, "");
                    case PARAM_DEFAULT_TEMPLATE -> argMap.put(PARAM_DEFAULT_TEMPLATE, "");
                    case PARAM_IS_DEV -> argMap.put(PARAM_IS_DEV, "");
                }
            }
        }
        return argMap;
    }

    private static class JsonUtil {
        public static String toJson(Object object) throws IllegalAccessException {
            if (object == null) {
                return "null";
            } else if (object.getClass().isArray()) {
                return arrayToJson(object);
            } else if (object instanceof Number || object instanceof Boolean) {
                return object.toString();
            } else if (object instanceof String) {
                return "\"" + object + "\"";
            } else {
                return objectToJson(object);
            }
        }

        private static String arrayToJson(Object array) throws IllegalAccessException {
            int length = Array.getLength(array);
            StringJoiner arrayElements = new StringJoiner(", ", "[", "]");
            for (int i = 0; i < length; i++) {
                Object element = Array.get(array, i);
                arrayElements.add(toJson(element));
            }
            return arrayElements.toString();
        }

        private static String objectToJson(Object object) throws IllegalAccessException {
            StringJoiner jsonElements = new StringJoiner(", ", "{", "}");
            Field[] fields = object.getClass().getDeclaredFields();
            for (Field field : fields) {
                field.setAccessible(true);
                String name = field.getName();
                Object value = field.get(object);
                jsonElements.add("\"" + name + "\": " + toJson(value));
            }
            return jsonElements.toString();
        }

        private static int read(Reader reader) {
            try {
                int ch = reader.read();
                while (ch == '\n' || ch == '\r' || ch == '\t' || ch == '\b') {
                    ch = reader.read();
                }
                return ch;
            } catch (IOException e) {
                throw new RuntimeException(e.getMessage());
            }
        }

        public static Map<String, Object> fromJSON(String json) {
            var result = new HashMap<String, Object>();
            StringReader reader = new StringReader(json.substring(1, json.length() - 1));
            int ch = read(reader);
            String key = null;
            String objKey = null;
            String arrKey = null;
            Map<String, Object> inner = null;
            List<Object> array = null;
            boolean isArray = false;
            while (ch != -1) {
                switch ((char) ch) {
                    case '{' -> {
                        if (inner != null) {
                            throw new RuntimeException("Maximum depth is 1");
                        }
                        objKey = key;
                        inner = new HashMap<>();
                        key = null;
                    }
                    case '}' -> {
                        if (inner != null) {
                            result.put(objKey, inner);
                            inner = null;
                            objKey = null;
                        }
                    }
                    case '[' -> {
                        if (isArray) {
                            throw new RuntimeException("Maximum depth is 1");
                        }
                        arrKey = key;
                        isArray = true;
                        array = new ArrayList<>();
                        key = null;
                    }
                    case ']' -> {
                        if (!isArray) {
                            throw new RuntimeException("Array is not started");
                        }
                        isArray = false;
                        if (inner != null) {
                            inner.put(arrKey, array);
                        } else {
                            result.put(arrKey, array);
                        }
                        arrKey = null;
                        array = null;
                    }
                    case '"' -> {
                        var sb = new StringBuilder();
                        ch = read(reader);
                        while (ch != -1 && (char) ch != '"') {
                            sb.append((char) ch);
                            ch = read(reader);
                        }
                        if (key == null && !isArray) {
                            key = sb.toString();
                        } else if (isArray) {
                            array.add(sb.toString());
                        } else if (inner != null) {
                            inner.put(key, sb.toString());
                            key = null;
                        } else {
                            result.put(key, sb.toString());
                            key = null;
                        }
                    }
                    case 't' -> {
                        StringBuilder sb = new StringBuilder();
                        sb.append((char) ch);
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        if (isArray) {
                            array.add(Boolean.valueOf(sb.toString()));
                        } else if (inner != null) {
                            inner.put(key, Boolean.valueOf(sb.toString()));
                            key = null;
                        } else {
                            result.put(key, Boolean.valueOf(sb.toString()));
                            key = null;
                        }
                    }
                    case 'f' -> {
                        var sb = new StringBuilder();
                        sb.append((char) ch);
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        if (isArray) {
                            array.add(Boolean.valueOf(sb.toString()));
                        } else if (inner != null) {
                            inner.put(key, Boolean.valueOf(sb.toString()));
                            key = null;
                        } else {
                            result.put(key, Boolean.valueOf(sb.toString()));
                            key = null;
                        }
                    }
                    case 'n' -> {
                        var sb = new StringBuilder();
                        sb.append(ch);
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        sb.append((char) read(reader));
                        if (sb.toString().equals("null")) {
                            if (isArray) {
                                array.add(null);
                            } else if (inner != null) {
                                inner.put(key, null);
                                key = null;
                            } else {
                                result.put(key, null);
                                key = null;
                            }
                        } else {
                            throw new RuntimeException("Invalid value");
                        }
                    }
                    case '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> {
                        var sb = new StringBuilder();
                        var flo = false;
                        while (ch != -1 && (Character.isDigit((char) ch) || (char) ch == '.')) {
                            sb.append((char) ch);
                            if ((char) ch == '.') {
                                flo = true;
                            }
                            ch = read(reader);
                        }
                        Object value = flo ? Double.parseDouble(sb.toString()) : Integer.parseInt(sb.toString());
                        if (isArray) {
                            array.add(value);
                        } else if (inner == null) {
                            result.put(key, value);
                            key = null;
                        } else {
                            inner.put(key, value);
                            key = null;
                        }
                    }
                    case ' ', ',', ':' -> {
                    }
                    default -> throw new RuntimeException("Unrecognizable value start: " + json.charAt(1));
                }
                ch = read(reader);
            }

            return result;
        }

        public static Map<String, Object> fromJSON(InputStream source) {
            String result = new BufferedReader(new InputStreamReader(source))
                    .lines().collect(Collectors.joining("\n"));
            return fromJSON(result);
        }
    }

    private static final String PARAM_CONFIG = "config";
    private static final String PARAM_HELP = "help";
    private static final String PARAM_API = "api";
    private static final String PARAM_IS_DEV = "dry-run";
    private static final String PARAM_DEFAULT_TEMPLATE = "default-template";
    private static final String PARAM_EASYRSA = "easyrsa";
    private static final String PARAM_OUTPUT = "output";
    private static final String PARAM_TEMPLATE = "template";
    private static final String PARAM_VPNURL = "vpnurl";
    private static final String PARAM_VPNPORT = "vpnport";
    private static final String PARAM_CA = "ca";
    private static final String PARAM_TLSAUTH = "tlsauth";
    private static final String PARAM_URL = "url";
    private static final String PARAM_PORT = "port";
    private static final String PARAM_CONTEXT = "context";
    private static final String PARAM_STATIC = "static";
    private static final String PATH_CLIENT_PROFILES = "client_profiles";
    private static final Integer DEFAULT_PORT = 8666;
    private static final String DEFAULT_IP = "127.0.0.1";
    private static final String DEFAULT_STATIC = "webapp";


    private static final String HELP_MESSSAGE = """
            \nWelcome to VPM management service. This server working with OpenVPN profiles.
            --------------------------------------------------------------------------------------------------------------------
            Requirements:
                - Security keys are generated with easyrsa utility
                - Unix environment supported only
            --------------------------------------------------------------------------------------------------------------------
            Common parameters:
                --%s              - prints this message
                --%s               - prints API
                --%s  - prints default client profile
                --%s            - could be used instead of setting command-line parameters below.
                                      Should be in Java properties format.
                --%s           - development mode. No actual easyrsa calls performed.
                
            VPN management parameters:
            |         Parameter           | Optional |                            Description                                  |
            | --------------------------- | -------- | ------------------------------------------------------------------------|
            | --%s=<easyrsa_dir>     |   false  | Path to EasyRSA installation dir                                        |
            | --%s=<output_dir>       |   true   | Path to dir for client profiles. `<easyrsa>/%s` is default |
            | --%s=<template_path>  |   true   | Path to client profile template                                         |
            | --%s=<vpnserver_url>    |   true   | IP/DN to VPN server. Required if `--template` is not specified          |
            | --%s=<vpnserver_port>  |   true   | VPN server port. Required if `--template` is not specified              |
            | --%s=<ca.crt path>          |   true   | VPN server ca.crt file. Required if `--template` is not specified       |
            | --%s=<.tlsauth path>   |   true   | VPN server tlsauth file. Required if `--template` is not specified      |
            --------------------------------------------------------------------------------------------------------------------
            Server parameters:
            |         Parameter           | Optional |                            Description                                  |
            | --------------------------- | -------- | ------------------------------------------------------------------------|
            | --%s                       |   true   | IP/DN on which server will be started. %s is default             |
            | --%s                      |   true   | Port for service API. %d is default                                   |
            | --%s                   |   true   | Secret context for managing profiles API. Random UUID if not specified  |
            | --%s                    |   true   | Path to dir with static content with UI. %s is default              |
            --------------------------------------------------------------------------------------------------------------------
                
            For more information please visit https://github.com/tar/vpnman. Thank you for using.
            """.formatted(PARAM_HELP, PARAM_API, PARAM_DEFAULT_TEMPLATE, PARAM_CONFIG, PARAM_IS_DEV, PARAM_EASYRSA, PARAM_OUTPUT, PATH_CLIENT_PROFILES, PARAM_TEMPLATE, PARAM_VPNURL, PARAM_VPNPORT,
            PARAM_CA, PARAM_TLSAUTH, PARAM_URL, DEFAULT_IP, PARAM_PORT, DEFAULT_PORT, PARAM_CONTEXT, PARAM_STATIC, DEFAULT_STATIC);

    private static final String defaultTemplate =
            """
                    client
                    tls-client
                    dev tun
                    topology subnet
                    pull
                    proto udp
                    remote ${VPN_SERVER_IP} ${VPN_SERVER_PORT}
                    resolv-retry infinite
                    nobind
                    user nobody
                    group nogroup
                    persist-key
                    persist-tun
                    mute-replay-warnings
                    <ca>
                    ${CA_CERTIFICATE}
                    </ca>
                    <cert>
                    ${CERT}
                    </cert>
                    <key>
                    ${KEY}
                    </key>
                    <tls-crypt>
                    ${TLSAUTH_CERTIFICATE}
                    </tls-crypt>
                    cipher AES-256-CBC
                    remote-cert-tls server
                    comp-lzo
                    verb 3
                    ;mute 20
                    """;
    private static final String API = """
            openapi: 3.0.0
            info:
              description: VPNMan API
              version: "1.0.0"
              title: Profiles API
              contact:
                email: an.lukashin@gmail.com
                url: https://github.com/tar/vpnman
              license:
                name: MIT License
                url: 'https://opensource.org/license/mit/'
            paths:
              /:
                get:
                  summary: return index.html
                  operationId: indexHTML
                  description: 'returns index.html if present'
                  responses:
                    '200':
                      description: index.html found
                    '404':
                      description: index.html not found
              /${secret}/profiles:
                parameters:
                  - name: secret
                    in: path
                    required: true
                    description: Secret part of path to secure access
                    schema:
                      type: string
                get:
                  summary: return all profiles
                  operationId: getProfiles
                  description: 'returns all active VPN profiles'
                  responses:
                    '200':
                      description: All profiles loaded
                      content:
                        application/json:
                          schema:
                            type: array
                            items:
                              $ref: '#/components/schemas/Profile'
                post:
                  summary: create new VPN profile
                  operationId: createProfile
                  description: Creates new VPN profile
                  responses:
                    '201':
                      description: profile created
                    '400':
                      description: 'Name is not specified or invalid'
                  requestBody:
                    content:
                      application/json:
                        schema:
                          $ref: '#/components/schemas/ProfileName'
                    description: Name for new profile
                put:
                  summary: update all profils
                  operationId: updateProfiles
                  description: Removes all .ovpn files and recreates them from easyrsa log
                  responses:
                    '200':
                      description: "Successfull updated"
              /${secret}/profiles/${name}:
                parameters:
                  - name: secret
                    in: path
                    required: true
                    description: Secret part of path to secure access
                    schema:
                      type: string
                  - name: name
                    in: path
                    required: true
                    description: profile name
                    schema:
                      type: string
                delete:
                  summary: Revoke access and delete profile
                  operationId: deleteProfile
                  description: Revokes access with easyrsa and deletes .ovpn file
                  responses:
                    '200':
                      description: Profile successfully deleted
                    '404':
                      description: Profile not found
              /direct/${hash}:
                parameters:
                  - name: hash
                    in: path
                    required: true
                    description: profile hash
                    schema:
                      type: string
                get:
                  summary: Download profile by hash
                  operationId: downloadProfile
                  description: Finds profile by hash and retrievs as file
                  responses:
                    '200':
                      description: Profile found
                      content:
                        text/plain:
                          schema:
                            type: string
                            format: binary
              /static/${path}:
                parameters:
                  - name: path
                    in: path
                    required: true
                    description: path to file in static folder
                    schema:
                      type: string
                get:
                  summary: Download files from static folder
                  operationId: downlodStatic
                  description: Dowloads any static file from static folder
                  responses:
                    '200':
                      description: File found
                      content:
                        text/plain:
                          schema:
                            type: string
                            format: binary
                    '404':
                      description: File not found
            components:
              schemas:
                Profile:
                  type: object
                  properties:
                    name:
                      type: string
                      example: john.smith
                    profile:
                      type: string
                      example: /path_to_dir/john.smith.ovpn
                    hash:
                      type: string
                      example: 'a30b62ed1b73534c59f34367a87765a42b4cf3e2b6852f327600286567f18fc0'
                ProfileName:
                  type: object
                  required:
                    - name
                  properties:
                    name:
                      type: string
                      example: john.smith
            """;
}