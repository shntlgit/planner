import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.stream.Collectors;

public class TaskServer {

    static final String DB_URL  = System.getenv().getOrDefault("MYSQL_URL", "jdbc:mysql://localhost:3306/taskflow_db");
    static final String DB_USER = System.getenv().getOrDefault("MYSQL_USER", "root");
    static final String DB_PASS = System.getenv().getOrDefault("MYSQL_PASSWORD", "");
    static final int    PORT    = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

    public static void main(String[] args) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/register",          new RegisterHandler());
        server.createContext("/login",             new LoginHandler());
        server.createContext("/addTask",           new AddTaskHandler());
        server.createContext("/getTasks",          new GetTasksHandler());
        server.createContext("/updateTask",        new UpdateTaskHandler());
        server.createContext("/updateTaskStatus",  new UpdateTaskStatusHandler());
        server.createContext("/deleteTask",        new DeleteTaskHandler());
        server.createContext("/updateProfile",     new UpdateProfileHandler());
        server.createContext("/getProfile",        new GetProfileHandler());
        server.createContext("/",                  new StaticHandler());

        server.setExecutor(null);
        server.start();
        System.out.println("==============================================");
        System.out.println("  TaskFlow Server running on port " + PORT);
        System.out.println("  Open register.html to get started!");
        System.out.println("==============================================");
    }

    static Connection getConnection() throws SQLException {
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    static void sendJson(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin",  "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody();
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            return br.lines().collect(Collectors.joining());
        }
    }

    static boolean handlePreflight(HttpExchange ex) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod())) {
            sendJson(ex, 204, "");
            return true;
        }
        return false;
    }

    static String jsonGet(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return null;
        int colon = json.indexOf(':', idx);
        int start = colon + 1;
        while (start < json.length() && json.charAt(start) == ' ') start++;
        if (start >= json.length()) return null;
        if (json.charAt(start) == '"') {
            int end = start + 1;
            while (end < json.length()) {
                if (json.charAt(end) == '"' && json.charAt(end - 1) != '\\') break;
                end++;
            }
            return json.substring(start + 1, end)
                       .replace("\\\"", "\"")
                       .replace("\\n", "\n")
                       .replace("\\r", "\r")
                       .replace("\\\\", "\\");
        } else if (json.charAt(start) == 'n') {
            return null;
        } else {
            int end = start;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(start, end).trim();
        }
    }

    // ── STATIC FILE HANDLER ──
    static class StaticHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/")) path = "/login.html";
            File file = new File("." + path);
            if (!file.exists()) {
                String msg = "404 Not Found";
                ex.sendResponseHeaders(404, msg.length());
                ex.getResponseBody().write(msg.getBytes());
                ex.getResponseBody().close();
                return;
            }
            String contentType = path.endsWith(".html") ? "text/html" :
                                 path.endsWith(".css")  ? "text/css"  :
                                 path.endsWith(".js")   ? "application/javascript" : "text/plain";
            ex.getResponseHeaders().add("Content-Type", contentType);
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.getResponseBody().close();
        }
    }

    // ── REGISTER ──
    static class RegisterHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body     = readBody(ex);
            String username = jsonGet(body, "username");
            String password = jsonGet(body, "password");
            if (username == null || password == null || username.isEmpty() || password.isEmpty()) {
                sendJson(ex, 400, "{\"message\":\"Username and password are required.\"}"); return;
            }
            try (Connection conn = getConnection()) {
                PreparedStatement check = conn.prepareStatement("SELECT id FROM users WHERE username = ?");
                check.setString(1, username);
                if (check.executeQuery().next()) {
                    sendJson(ex, 409, "{\"message\":\"Username already taken.\"}"); return;
                }
                PreparedStatement insert = conn.prepareStatement("INSERT INTO users (username, password) VALUES (?, ?)");
                insert.setString(1, username);
                insert.setString(2, password);
                insert.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Registration successful.\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error: " + e.getMessage() + "\"}");
            }
        }
    }

    // ── LOGIN ──
    static class LoginHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body     = readBody(ex);
            String username = jsonGet(body, "username");
            String password = jsonGet(body, "password");
            if (username == null || password == null) {
                sendJson(ex, 400, "{\"message\":\"Missing credentials.\"}"); return;
            }
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE username = ? AND password = ?");
                ps.setString(1, username);
                ps.setString(2, password);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    int uid = rs.getInt("id");
                    sendJson(ex, 200, "{\"message\":\"Login successful.\",\"userId\":" + uid + ",\"username\":\"" + username + "\"}");
                } else {
                    sendJson(ex, 401, "{\"message\":\"Invalid username or password.\"}");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── ADD TASK ──
    static class AddTaskHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body      = readBody(ex);
            String userIdStr = jsonGet(body, "userId");
            String taskName  = jsonGet(body, "taskName");
            String deadline  = jsonGet(body, "deadline");
            if (userIdStr == null || taskName == null || deadline == null) {
                sendJson(ex, 400, "{\"message\":\"Missing fields.\"}"); return;
            }
            deadline = deadline.replace("T", " ") + ":00";
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO tasks (user_id, task_name, deadline) VALUES (?, ?, ?)");
                ps.setInt(1, Integer.parseInt(userIdStr));
                ps.setString(2, taskName);
                ps.setString(3, deadline);
                ps.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Task added.\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── GET TASKS ──
    static class GetTasksHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String query  = ex.getRequestURI().getQuery();
            String userId = null;
            if (query != null && query.startsWith("userId=")) userId = query.substring(7);
            if (userId == null) { sendJson(ex, 400, "{\"message\":\"userId required.\"}"); return; }
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, task_name, deadline, status FROM tasks WHERE user_id = ? ORDER BY deadline ASC");
                ps.setInt(1, Integer.parseInt(userId));
                ResultSet rs = ps.executeQuery();
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                while (rs.next()) {
                    if (!first) sb.append(",");
                    first = false;
                    String dl = rs.getTimestamp("deadline").toLocalDateTime().toString();
                    sb.append("{")
                      .append("\"id\":").append(rs.getInt("id")).append(",")
                      .append("\"taskName\":\"").append(escape(rs.getString("task_name"))).append("\",")
                      .append("\"deadline\":\"").append(dl).append("\",")
                      .append("\"status\":\"").append(rs.getString("status")).append("\"")
                      .append("}");
                }
                sb.append("]");
                sendJson(ex, 200, sb.toString());
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── UPDATE TASK (name + deadline) ──
    static class UpdateTaskHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body     = readBody(ex);
            String idStr    = jsonGet(body, "id");
            String taskName = jsonGet(body, "taskName");
            String deadline = jsonGet(body, "deadline");
            if (idStr == null || taskName == null || deadline == null) {
                sendJson(ex, 400, "{\"message\":\"Missing fields.\"}"); return;
            }
            deadline = deadline.replace("T", " ") + ":00";
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                    "UPDATE tasks SET task_name = ?, deadline = ? WHERE id = ?");
                ps.setString(1, taskName);
                ps.setString(2, deadline);
                ps.setInt(3, Integer.parseInt(idStr));
                ps.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Task updated.\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── UPDATE TASK STATUS ──
    static class UpdateTaskStatusHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body   = readBody(ex);
            String idStr  = jsonGet(body, "id");
            String status = jsonGet(body, "status");
            if (idStr == null || status == null) {
                sendJson(ex, 400, "{\"message\":\"Missing fields.\"}"); return;
            }
            if (!status.equals("pending") && !status.equals("completed")) {
                sendJson(ex, 400, "{\"message\":\"Invalid status.\"}"); return;
            }
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement("UPDATE tasks SET status = ? WHERE id = ?");
                ps.setString(1, status);
                ps.setInt(2, Integer.parseInt(idStr));
                ps.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Status updated.\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── DELETE TASK ──
    static class DeleteTaskHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body  = readBody(ex);
            String idStr = jsonGet(body, "id");
            if (idStr == null) { sendJson(ex, 400, "{\"message\":\"Missing task id.\"}"); return; }
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement("DELETE FROM tasks WHERE id = ?");
                ps.setInt(1, Integer.parseInt(idStr));
                ps.executeUpdate();
                sendJson(ex, 200, "{\"message\":\"Task deleted.\"}");
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── GET PROFILE ──
    static class GetProfileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String query  = ex.getRequestURI().getQuery();
            String userId = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    if (param.startsWith("userId=")) { userId = param.substring(7); break; }
                }
            }
            if (userId == null) { sendJson(ex, 400, "{\"message\":\"userId required.\"}"); return; }
            try (Connection conn = getConnection()) {
                PreparedStatement ps = conn.prepareStatement(
                    "SELECT username, bio, profile_pic FROM users WHERE id = ?");
                ps.setInt(1, Integer.parseInt(userId));
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    String bio   = rs.getString("bio");
                    String pic   = rs.getString("profile_pic");
                    String uname = rs.getString("username");
                    sendJson(ex, 200,
                        "{\"username\":\"" + escape(uname) + "\"," +
                        "\"bio\":"         + (bio == null ? "null" : "\"" + escape(bio) + "\"") + "," +
                        "\"profile_pic\":" + (pic == null ? "null" : "\"" + escape(pic) + "\"") +
                        "}");
                } else {
                    sendJson(ex, 404, "{\"message\":\"User not found.\"}");
                }
            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error.\"}");
            }
        }
    }

    // ── UPDATE PROFILE ──
    static class UpdateProfileHandler implements HttpHandler {
        public void handle(HttpExchange ex) throws IOException {
            if (handlePreflight(ex)) return;
            String body      = readBody(ex);
            String userIdStr = jsonGet(body, "userId");
            String newUser   = jsonGet(body, "username");
            String newPass   = jsonGet(body, "password");
            String newBio    = jsonGet(body, "bio");
            String newPic    = jsonGet(body, "profile_pic");

            if (userIdStr == null) {
                sendJson(ex, 400, "{\"message\":\"Missing userId.\"}"); return;
            }

            try (Connection conn = getConnection()) {
                if (newUser != null && !newUser.isEmpty()) {
                    PreparedStatement check = conn.prepareStatement(
                        "SELECT id FROM users WHERE username = ? AND id != ?");
                    check.setString(1, newUser);
                    check.setInt(2, Integer.parseInt(userIdStr));
                    if (check.executeQuery().next()) {
                        sendJson(ex, 409, "{\"message\":\"Username already taken.\"}"); return;
                    }
                }

                boolean hasUser = newUser != null && !newUser.isEmpty();
                boolean hasPass = newPass != null && !newPass.isEmpty();
                boolean hasBio  = newBio  != null;
                boolean hasPic  = newPic  != null && !newPic.isEmpty();

                if (!hasUser && !hasPass && !hasBio && !hasPic) {
                    sendJson(ex, 400, "{\"message\":\"Nothing to update.\"}"); return;
                }

                StringBuilder sql = new StringBuilder("UPDATE users SET ");
                boolean first = true;
                if (hasUser) { sql.append("username = ?");    first = false; }
                if (hasPass) { if (!first) sql.append(", "); sql.append("password = ?");    first = false; }
                if (hasBio)  { if (!first) sql.append(", "); sql.append("bio = ?");         first = false; }
                if (hasPic)  { if (!first) sql.append(", "); sql.append("profile_pic = ?"); }
                sql.append(" WHERE id = ?");

                PreparedStatement ps = conn.prepareStatement(sql.toString());
                int idx = 1;
                if (hasUser) ps.setString(idx++, newUser);
                if (hasPass) ps.setString(idx++, newPass);
                if (hasBio)  ps.setString(idx++, newBio.isEmpty() ? null : newBio);
                if (hasPic)  ps.setString(idx++, newPic);
                ps.setInt(idx, Integer.parseInt(userIdStr));
                ps.executeUpdate();

                String returnedUsername;
                if (hasUser) {
                    returnedUsername = newUser;
                } else {
                    PreparedStatement fetchUser = conn.prepareStatement(
                        "SELECT username FROM users WHERE id = ?");
                    fetchUser.setInt(1, Integer.parseInt(userIdStr));
                    ResultSet rs = fetchUser.executeQuery();
                    returnedUsername = rs.next() ? rs.getString("username") : "";
                }

                sendJson(ex, 200,
                    "{\"message\":\"Profile updated.\"," +
                    "\"username\":\"" + escape(returnedUsername) + "\"}");

            } catch (SQLException e) {
                e.printStackTrace();
                sendJson(ex, 500, "{\"message\":\"Database error: " + e.getMessage() + "\"}");
            }
        }
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}