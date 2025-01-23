package telegram.files;

import cn.hutool.core.io.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import telegram.files.Transfer.DuplicationPolicy;
import telegram.files.Transfer.TransferPolicy;
import telegram.files.repository.FileRecord;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TransferTest {
    private Transfer transfer;

    private FileRecord mockFileRecord;

    private Consumer<Transfer.TransferStatusUpdated> mockStatusUpdater;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        mockFileRecord = mock(FileRecord.class);
        mockStatusUpdater = mock(Consumer.class);

        transfer = Transfer.create(TransferPolicy.GROUP_BY_CHAT);
        transfer.destination = tempDir.toString();
        transfer.duplicationPolicy = DuplicationPolicy.OVERWRITE;
        transfer.transferStatusUpdated = mockStatusUpdater;
    }

    @Test
    void testCreateTransfer() {
        Transfer chatTransfer = Transfer.create(TransferPolicy.GROUP_BY_CHAT);
        Transfer typeTransfer = Transfer.create(TransferPolicy.GROUP_BY_TYPE);

        assertNotNull(chatTransfer);
        assertNotNull(typeTransfer);
        assertInstanceOf(Transfer.GroupByChat.class, chatTransfer);
        assertInstanceOf(Transfer.GroupByType.class, typeTransfer);
    }

    @Test
    void testTransferSuccessful(@TempDir Path tempDir) {
        // Prepare mock file record
        String fileName = "source.txt";
        String sourcePath = mockWaitingTransfer(tempDir, fileName);

        // Transfer
        transfer.transfer(mockFileRecord);

        // Verify status updates and file moved
        verify(mockStatusUpdater, times(2)).accept(any());

        // Check final destination
        Path expectedDestination = Path.of(transfer.destination).resolve("456").resolve("789").resolve(fileName);
        assertTrue(Files.exists(expectedDestination));
        assertFalse(Files.exists(Paths.get(sourcePath)));
    }

    @Test
    void testDuplicationPolicySkip(@TempDir Path tempDir) {
        String fileName = "source.txt";

        // Prepare existing file
        createExistingFile(fileName);

        // Setup mock
        mockWaitingTransfer(tempDir, fileName);

        // Set skip policy
        transfer.duplicationPolicy = DuplicationPolicy.SKIP;

        // Transfer
        transfer.transfer(mockFileRecord);

        // Verify status updates
        verify(mockStatusUpdater, times(1)).accept(argThat(
                status -> status.transferStatus() == FileRecord.TransferStatus.idle
        ));
    }

    @Test
    void testDuplicationPolicyRename(@TempDir Path tempDir) {
        String fileName = "source.txt";
        // Prepare existing file
        String existingFile = createExistingFile(fileName);

        // Setup mock
        mockWaitingTransfer(tempDir, fileName);

        // Set rename policy
        transfer.duplicationPolicy = DuplicationPolicy.RENAME;

        // Transfer
        transfer.transfer(mockFileRecord);

        // Verify a new file was created with a suffix
        File[] filesInDir = FileUtil.ls(Path.of(existingFile).getParent().toString());
        assertTrue(filesInDir.length >= 2);
        assertTrue(Arrays.stream(filesInDir)
                .anyMatch(f -> f.getName().contains("source-1.txt")));
    }

    @Test
    void testTransferError(@TempDir Path tempDir) {
        // Force an error by providing an invalid path
        when(mockFileRecord.id()).thenReturn(123);
        when(mockFileRecord.localPath()).thenReturn("/invalid/nonexistent/path");

        // Transfer
        transfer.transfer(mockFileRecord);

        // Verify error status update
        verify(mockStatusUpdater, times(1)).accept(argThat(
                status -> status.transferStatus() == FileRecord.TransferStatus.error
        ));
    }

    private String mockWaitingTransfer(Path tempDir, String fileName) {
        String sourcePath = tempDir.resolve(fileName).toString();
        FileUtil.writeUtf8String("test content", sourcePath);

        when(mockFileRecord.id()).thenReturn(123);
        when(mockFileRecord.localPath()).thenReturn(sourcePath);
        when(mockFileRecord.telegramId()).thenReturn(456L);
        when(mockFileRecord.chatId()).thenReturn(789L);

        return sourcePath;
    }

    private String createExistingFile(String fileName) {
        Path existingFile = Path.of(transfer.destination).resolve("456").resolve("789").resolve(fileName);
        FileUtil.createTempFile(existingFile.getParent().toFile());
        FileUtil.writeUtf8String("existing content", existingFile.toString());

        return existingFile.toString();
    }

}
