package usbcontrol.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 매일 새벽 데이터베이스를 backup 폴더에 압축 파일로 백업하고, 오래된 백업은 지웁니다. */
@Service
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final Path backupDir;
    private final int keepCount;

    public BackupService(JdbcTemplate jdbc, AuditService audit,
                         @Value("${usbcontrol.backup.dir:./backup}") String backupDir,
                         @Value("${usbcontrol.backup.keep:60}") int keepCount) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.backupDir = Path.of(backupDir).toAbsolutePath().normalize();
        this.keepCount = keepCount;
    }

    @Scheduled(cron = "${usbcontrol.backup.cron:0 30 2 * * *}")
    public void scheduledBackup() {
        try {
            Path file = backupNow();
            audit.log("(자동)", "자동 백업", file.getFileName().toString());
        } catch (Exception e) {
            log.error("자동 백업 실패", e);
            audit.log("(자동)", "자동 백업 실패", e.getMessage());
        }
    }

    public synchronized Path backupNow() throws IOException {
        Files.createDirectories(backupDir);
        Path file = backupDir.resolve("usbcontrol-" + LocalDateTime.now().format(NAME) + ".zip");
        // H2 의 BACKUP 명령은 서버가 돌아가는 중에도 일관된 백업을 만듭니다.
        jdbc.execute("BACKUP TO '" + file.toString().replace("'", "''") + "'");
        deleteOld();
        return file;
    }

    public Path getBackupDir() {
        return backupDir;
    }

    public List<Path> list() throws IOException {
        if (!Files.isDirectory(backupDir)) return List.of();
        try (Stream<Path> files = Files.list(backupDir)) {
            return files.filter(p -> p.getFileName().toString().matches("usbcontrol-\\d{8}-\\d{6}\\.zip"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .toList();
        }
    }

    private void deleteOld() throws IOException {
        List<Path> all = list();
        for (Path old : all.subList(Math.min(keepCount, all.size()), all.size())) {
            Files.deleteIfExists(old);
        }
    }
}
