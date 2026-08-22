package com.agent;

import com.agent.llm.LlmClient;
import com.agent.tool.ToolRegistry;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Agent 冒烟测试 - 验证核心组件是否正常工作
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AgentTest {

    @TempDir
    Path tempDir;

    private ToolRegistry toolRegistry;

    @BeforeEach
    void setUp() {
        toolRegistry = new ToolRegistry(tempDir);
    }

    // ==================== ToolRegistry 测试 ====================

    @Test
    @Order(1)
    @DisplayName("测试工具注册 - 验证所有默认工具已注册")
    void testToolRegistration() {
        var tools = toolRegistry.getToolNames();
        
        assertTrue(tools.contains("read_file"), "read_file 工具应已注册");
        assertTrue(tools.contains("write_file"), "write_file 工具应已注册");
        assertTrue(tools.contains("list_dir"), "list_dir 工具应已注册");
        assertTrue(tools.contains("glob_files"), "glob_files 工具应已注册");
        assertTrue(tools.contains("grep_code"), "grep_code 工具应已注册");
        assertTrue(tools.contains("execute_command"), "execute_command 工具应已注册");
        assertTrue(tools.contains("create_project"), "create_project 工具应已注册");
        assertTrue(tools.contains("save_memory"), "save_memory 工具应已注册");
        
        assertEquals(8, tools.size());
        
        System.out.println("✓ 已注册工具: " + tools);
    }

    @Test
    @Order(2)
    @DisplayName("测试工具定义 - 验证 LLM 工具定义格式")
    void testToolDefinitions() {
        var definitions = toolRegistry.getToolDefinitions();
        
        assertNotNull(definitions);
        assertFalse(definitions.isEmpty());
        
        for (var tool : definitions) {
            assertNotNull(tool.name(), "工具名称不应为空");
            assertNotNull(tool.description(), "工具描述不应为空");
            assertNotNull(tool.parameters(), "工具参数定义不应为空");
            
            System.out.println("  工具: " + tool.name() + " - " + tool.description().substring(0, Math.min(40, tool.description().length())));
        }
        
        System.out.println("✓ 工具定义数量: " + definitions.size());
    }

    // ==================== read_file 测试 ====================

    @Test
    @Order(10)
    @DisplayName("测试 read_file - 读取存在的文件")
    void testReadFile() throws IOException {
        // 创建测试文件
        Path testFile = tempDir.resolve("test.txt");
        Files.writeString(testFile, "Hello, World!");

        // 执行读文件
        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"test.txt\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        assertEquals(1, results.size());
        assertEquals("Hello, World!", results.get(0).result());
        System.out.println("✓ read_file 测试通过");
    }

    @Test
    @Order(11)
    @DisplayName("测试 read_file - 文件不存在")
    void testReadFileNotFound() {
        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"nonexistent.txt\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        assertTrue(results.get(0).result().contains("错误"));
        System.out.println("✓ read_file 不存在测试通过");
    }

    // ==================== write_file 测试 ====================

    @Test
    @Order(20)
    @DisplayName("测试 write_file - 写入新文件")
    void testWriteFile() {
        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file", "{\"path\":\"output.txt\",\"content\":\"测试内容\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        assertTrue(results.get(0).result().contains("成功"));

        // 验证文件实际存在
        assertTrue(Files.exists(tempDir.resolve("output.txt")));
        System.out.println("✓ write_file 测试通过");
    }

    @Test
    @Order(21)
    @DisplayName("测试 write_file - 覆盖已有文件")
    void testWriteFileOverwrite() throws IOException {
        Path testFile = tempDir.resolve("overwrite.txt");
        Files.writeString(testFile, "原始内容");

        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file", "{\"path\":\"overwrite.txt\",\"content\":\"新内容\"}")
        );
        toolRegistry.executeTools(invocations);

        assertEquals("新内容", Files.readString(testFile));
        System.out.println("✓ write_file 覆盖测试通过");
    }

    // ==================== list_dir 测试 ====================

    @Test
    @Order(30)
    @DisplayName("测试 list_dir - 列出目录")
    void testListDir() throws IOException {
        // 创建一些测试文件
        Files.writeString(tempDir.resolve("file1.txt"), "content1");
        Files.writeString(tempDir.resolve("file2.txt"), "content2");
        Files.createDirectory(tempDir.resolve("subdir"));

        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "list_dir", "{\"path\":\".\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        String result = results.get(0).result();
        assertTrue(result.contains("file1.txt"));
        assertTrue(result.contains("file2.txt"));
        assertTrue(result.contains("subdir"));
        System.out.println("✓ list_dir 测试通过\n" + result);
    }

    // ==================== execute_command 测试 ====================

    @Test
    @Order(40)
    @DisplayName("测试 execute_command - 执行简单命令")
    void testExecuteCommand() {
        String os = System.getProperty("os.name").toLowerCase();
        String command;
        
        if (os.contains("win")) {
            command = "echo hello_from_test";
        } else {
            command = "echo hello_from_test";
        }

        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "execute_command", 
                        "{\"command\":\"" + command + "\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        assertTrue(results.get(0).result().contains("hello_from_test"));
        System.out.println("✓ execute_command 测试通过");
    }

    // ==================== 批量工具执行测试 ====================

    @Test
    @Order(50)
    @DisplayName("测试批量工具执行")
    void testBatchExecution() throws IOException {
        Path file1 = tempDir.resolve("batch1.txt");
        Path file2 = tempDir.resolve("batch2.txt");
        Files.writeString(file1, "Content 1");
        Files.writeString(file2, "Content 2");

        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"batch1.txt\"}"),
                new ToolRegistry.ToolInvocation("call_2", "read_file", "{\"path\":\"batch2.txt\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        assertEquals(2, results.size());
        assertEquals("Content 1", results.get(0).result());
        assertEquals("Content 2", results.get(1).result());
        System.out.println("✓ 批量执行测试通过");
    }

    // ==================== 路径安全测试 ====================

    @Test
    @Order(60)
    @DisplayName("测试路径安全 - 防止路径穿越")
    void testPathSecurity() {
        var invocations = List.of(
                new ToolRegistry.ToolInvocation("call_1", "read_file", "{\"path\":\"../../windows/system32/config/sam\"}")
        );
        var results = toolRegistry.executeTools(invocations);

        // 应该被拒绝
        assertTrue(results.get(0).result().contains("策略拒绝") || results.get(0).result().contains("路径"));
        System.out.println("✓ 路径安全测试通过");
    }

    @Test
    @Order(61)
    @DisplayName("测试 glob_files - 查找 java 文件")
    void testGlobFiles() throws IOException {
        Path src = tempDir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Hello.java"), "class Hello {}");

        var results = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("g1", "glob_files", "{\"pattern\":\"**/*.java\"}")
        ));

        assertTrue(results.get(0).result().contains("Hello.java"));
    }

    @Test
    @Order(62)
    @DisplayName("测试 grep_code - 搜索关键字")
    void testGrepCode() throws IOException {
        Files.writeString(tempDir.resolve("Demo.java"), "public class Demo { void run() {} }");

        var results = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("grep1", "grep_code", "{\"pattern\":\"class Demo\"}")
        ));

        assertTrue(results.get(0).result().contains("Demo.java"));
    }

    @Test
    @Order(63)
    @DisplayName("测试 create_project - 创建 Java 项目")
    void testCreateProject() {
        var results = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("p1", "create_project", "{\"name\":\"hello\",\"type\":\"java\"}")
        ));

        assertTrue(results.get(0).result().contains("项目已创建"));
        assertTrue(Files.exists(tempDir.resolve("hello/pom.xml")));
    }

    // ==================== Agent 基本功能测试 ====================

    @Test
    @Order(70)
    @DisplayName("测试 Agent 初始化")
    void testAgentInitialization() {
        // 使用 mock LLM 客户端进行测试
        LlmClient mockLlm = new LlmClient() {
            @Override
            public LlmClient.ChatResponse chat(List<LlmClient.Message> messages, List<LlmClient.Tool> tools) {
                return new LlmClient.ChatResponse("assistant", "测试响应", List.of(), 0, 0);
            }
        };

        Agent agent = new Agent(mockLlm, toolRegistry);
        
        assertNotNull(agent);
        assertEquals(1, agent.getConversationHistory().size()); // system prompt
        assertTrue(agent.getAvailableTools().contains("read_file"));
        System.out.println("✓ Agent 初始化测试通过");
    }

    @Test
    @Order(71)
    @DisplayName("测试 Agent reset")
    void testAgentReset() {
        LlmClient mockLlm = new LlmClient() {
            @Override
            public LlmClient.ChatResponse chat(List<LlmClient.Message> messages, List<LlmClient.Tool> tools) {
                return new LlmClient.ChatResponse("assistant", "test", List.of(), 0, 0);
            }
        };

        Agent agent = new Agent(mockLlm, toolRegistry);
        agent.run("你好");
        
        int historySizeBefore = agent.getConversationHistory().size();
        assertTrue(historySizeBefore > 1);

        agent.reset();
        assertEquals(1, agent.getConversationHistory().size()); // 只剩 system prompt
        System.out.println("✓ Agent reset 测试通过");
    }

    // ==================== 综合测试 ====================

    @Test
    @Order(100)
    @DisplayName("综合冒烟测试 - 验证整个流程")
    void testSmokeTest() throws IOException {
        System.out.println("\n═══ 综合冒烟测试 ═══\n");

        // 1. 创建文件
        System.out.println("1. 创建测试文件...");
        Files.writeString(tempDir.resolve("test_project.md"), """
                # Test Project
                This is a test file for smoke testing.
                """);

        // 2. 写入文件
        System.out.println("2. 测试 write_file...");
        var writeResult = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("w1", "write_file", 
                        "{\"path\":\"hello.txt\",\"content\":\"Hello Agent World!\"}")
        ));
        assertTrue(writeResult.get(0).result().contains("成功"));

        // 3. 读取文件
        System.out.println("3. 测试 read_file...");
        var readResult = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("r1", "read_file", 
                        "{\"path\":\"hello.txt\"}")
        ));
        assertEquals("Hello Agent World!", readResult.get(0).result());

        // 4. 列目录
        System.out.println("4. 测试 list_dir...");
        var listResult = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("l1", "list_dir", 
                        "{\"path\":\".\"}")
        ));
        assertTrue(listResult.get(0).result().contains("hello.txt"));

        // 5. 执行命令
        System.out.println("5. 测试 execute_command...");
        var cmdResult = toolRegistry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("c1", "execute_command", 
                        "{\"command\":\"echo smoke_test_passed\"}")
        ));
        assertTrue(cmdResult.get(0).result().contains("smoke_test_passed"));

        System.out.println("\n✅ 所有冒烟测试通过！");
    }
}
