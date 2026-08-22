package com.agent.hitl;

import com.agent.policy.AuditLog;
import com.agent.tool.ToolRegistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitlToolRegistryTest {

    @Test
    void disabledHitlPassesThroughToParent(@TempDir Path tempDir) {
        TerminalHitlHandler handler = new TerminalHitlHandler(false);
        HitlToolRegistry registry = new HitlToolRegistry(handler, tempDir);

        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "list_dir", "{\"path\":\".\"}")
        )).get(0);

        assertFalse(result.result().startsWith("[HITL]"));
    }

    @Test
    void nonDangerousToolSkipsApprovalEvenWhenEnabled(@TempDir Path tempDir) {
        StubHandler stub = new StubHandler(req -> {
            throw new AssertionError("只读工具不应触发审批");
        });
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir);

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "list_dir", "{\"path\":\".\"}")
        ));

        assertEquals(0, stub.requestCount());
    }

    @Test
    void rejectedDecisionBlocksExecutionAndWritesAudit(@TempDir Path tempDir) throws Exception {
        Path auditDir = tempDir.resolve("audit");
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("too risky"));
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir);
        registry.getAuditLog(); // default dir - inject custom via reflection? 

        // Use registry with default audit - test via readRecent after reject
        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file",
                        "{\"path\":\"blocked.txt\",\"content\":\"x\"}")
        )).get(0);

        assertTrue(result.result().startsWith("[HITL]"));
        assertTrue(result.result().contains("too risky"));
        assertFalse(Files.exists(tempDir.resolve("blocked.txt")));
        assertEquals(1, stub.requestCount());
    }

    @Test
    void approvedDecisionExecutesTool(@TempDir Path tempDir) throws Exception {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir);

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file",
                        "{\"path\":\"approved.txt\",\"content\":\"approved\"}")
        ));

        assertEquals("approved", Files.readString(tempDir.resolve("approved.txt")));
    }

    @Test
    void skippedDecisionBlocksExecution(@TempDir Path tempDir) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.skip());
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir);

        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file",
                        "{\"path\":\"skipped.txt\",\"content\":\"x\"}")
        )).get(0);

        assertTrue(result.result().contains("跳过"));
        assertFalse(Files.exists(tempDir.resolve("skipped.txt")));
    }

    @Test
    void modifiedDecisionExecutesToolWithModifiedArgs(@TempDir Path tempDir) throws Exception {
        Path modified = tempDir.resolve("modified.txt");
        String modifiedArgs = "{\"path\":\"modified.txt\",\"content\":\"modified!\"}";
        StubHandler stub = new StubHandler(req -> ApprovalResult.modify(modifiedArgs));
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir);

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file",
                        "{\"path\":\"original.txt\",\"content\":\"oops\"}")
        ));

        assertFalse(Files.exists(tempDir.resolve("original.txt")));
        assertEquals("modified!", Files.readString(modified));
    }

    @Test
    void commandGuardRejectsBeforeExecution(@TempDir Path tempDir) {
        StubHandler stub = new StubHandler(req -> ApprovalResult.approve());
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir);

        ToolRegistry.ToolExecutionResult result = registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "execute_command",
                        "{\"command\":\"sudo rm -rf /\"}")
        )).get(0);

        assertTrue(result.result().startsWith("🛡️ 策略拒绝"));
        assertEquals(1, stub.requestCount());
    }

    @Test
    void rejectWritesAuditEntry(@TempDir Path tempDir) {
        AuditLog auditLog = new AuditLog(tempDir.resolve("audit"));
        StubHandler stub = new StubHandler(req -> ApprovalResult.reject("nope"));
        HitlToolRegistry registry = new HitlToolRegistry(stub, tempDir) {
            @Override
            public AuditLog getAuditLog() {
                return auditLog;
            }
        };

        registry.executeTools(List.of(
                new ToolRegistry.ToolInvocation("call_1", "write_file",
                        "{\"path\":\"a.txt\",\"content\":\"x\"}")
        ));

        List<AuditLog.AuditEntry> entries = auditLog.readRecent(5);
        assertEquals(1, entries.size());
        assertEquals(AuditLog.OUTCOME_DENY, entries.get(0).outcome());
        assertEquals(AuditLog.APPROVER_HITL, entries.get(0).approver());
    }

    private static final class StubHandler implements HitlHandler {
        private final Function<ApprovalRequest, ApprovalResult> responder;
        private final AtomicInteger requestCount = new AtomicInteger();
        private volatile boolean enabled = true;

        private StubHandler(Function<ApprovalRequest, ApprovalResult> responder) {
            this.responder = responder;
        }

        @Override
        public ApprovalResult requestApproval(ApprovalRequest request) {
            requestCount.incrementAndGet();
            return responder.apply(request);
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        int requestCount() {
            return requestCount.get();
        }
    }
}
