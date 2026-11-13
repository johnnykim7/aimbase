package com.platform.session;

import com.platform.config.WorkspaceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * CR-187: 작업 폴더를 따로 붙인 실행기로 가는 대화는 그 실행기 폴더 아래에 대화 폴더를 잡는다.
 */
class ChatWorkspaceAssignerTest {

    @TempDir
    Path shared;

    @TempDir
    Path csRunner;

    private final Map<String, String> refs = new HashMap<>();
    private ChatWorkspaceAssigner assigner;

    @BeforeEach
    void setUp() {
        SessionStore store = mock(SessionStore.class);
        when(store.getWorkspaceRef(anyString())).thenAnswer(inv -> refs.get(inv.<String>getArgument(0)));
        doAnswer(inv -> refs.putIfAbsent(inv.getArgument(0), inv.getArgument(1)))
                .when(store).setWorkspaceRefIfAbsent(anyString(), anyString());

        WorkspaceProperties props = new WorkspaceProperties();
        props.setBase(shared.toString());
        props.setWhitelistRoots(List.of(shared.toString()));
        props.setRunnerBases(List.of("cli-runner-bp-wes-cs=" + csRunner));
        assigner = new ChatWorkspaceAssigner(store, props);
    }

    @Test
    void separatedRunner_getsFolderInsideItsOwnWorkspace() {
        String path = assigner.assign("sess-cs", "cli-runner-bp-wes-cs");

        assertEquals(csRunner.resolve("chat/sess-cs").toAbsolutePath().normalize().toString(), path);
        assertTrue(Files.isDirectory(Path.of(path)));
        assertFalse(Files.exists(shared.resolve("chat/sess-cs")));
    }

    @Test
    void sharedRunnerOrNoName_staysUnderBase() {
        assertEquals(shared.resolve("chat/sess-a").toAbsolutePath().normalize().toString(),
                assigner.assign("sess-a", "cli-runner-bp-wes"));
        assertEquals(shared.resolve("chat/sess-b").toAbsolutePath().normalize().toString(),
                assigner.assign("sess-b"));
    }

    @Test
    void existingFolder_isKeptEvenIfRunnerDiffers() {
        String first = assigner.assign("sess-x");
        assertEquals(first, assigner.assign("sess-x", "cli-runner-bp-wes-cs"));
    }

    @Test
    void sessionIdCannotEscapeRunnerChatFolder() {
        assertNull(assigner.assign("../escape", "cli-runner-bp-wes-cs"));
    }
}
