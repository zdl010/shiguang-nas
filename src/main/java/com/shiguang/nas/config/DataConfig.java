package com.shiguang.nas.config;

import com.shiguang.nas.common.FileSecurity;
import com.shiguang.nas.security.InstanceSecrets;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;

@Configuration
public class DataConfig {

    private static final Logger log = LoggerFactory.getLogger(DataConfig.class);

    /**
     * SQLite 连接池大小。
     *
     * <p>WAL 模式下是"多读单写"：读之间不互斥，写之间串行。池子开大并不会提升写吞吐，
     * 只会让更多线程排队等写锁，反而更容易撞上 SQLITE_BUSY。8 个连接对家用规模足够。
     */
    private static final int POOL_SIZE = 8;

    @Bean
    public AppPaths appPaths() {
        return AppPaths.initialize();
    }

    @Bean
    public InstanceSecrets instanceSecrets(AppPaths appPaths) {
        return InstanceSecrets.loadOrCreate(appPaths);
    }

    @Bean(destroyMethod = "close")
    public DataSource dataSource(AppPaths appPaths) {
        Path dbFile = appPaths.databaseFile();

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        // WAL：读写不互相阻塞，是本项目"一边上传一边浏览"场景的前提
        sqliteConfig.setJournalMode(SQLiteConfig.JournalMode.WAL);
        // 不设 busy_timeout 的话，并发上传必现 SQLITE_BUSY
        sqliteConfig.setBusyTimeout(5_000);
        // NORMAL 在 WAL 下已经足够安全（断电最多丢最后一个事务，不会损坏库）
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        sqliteConfig.enforceForeignKeys(true);

        SQLiteDataSource sqlite = new SQLiteDataSource(sqliteConfig);
        sqlite.setUrl("jdbc:sqlite:" + dbFile.toAbsolutePath());

        HikariConfig hikari = new HikariConfig();
        hikari.setDataSource(sqlite);
        hikari.setPoolName("shiguang-sqlite");
        hikari.setMaximumPoolSize(POOL_SIZE);
        hikari.setAutoCommit(true);
        HikariDataSource dataSource = new HikariDataSource(hikari);

        // 迁移必须在任何仓储 Bean 被使用前完成。放在这里而不是单独的 Bean，
        // 是为了让"建库 -> 迁移"成为一个不可能被 Bean 初始化顺序打乱的原子步骤。
        new SchemaMigrator(dataSource).migrate();

        hardenDatabaseFiles(dbFile);
        log.info("数据库就绪: {}", dbFile);
        return dataSource;
    }

    @Bean
    public JdbcClient jdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    /**
     * 数据库里存着全部媒体元数据和密码哈希，权限必须收到仅属主可读。
     * WAL 模式会额外产生 -wal 和 -shm 两个文件，同样要加固。
     */
    private void hardenDatabaseFiles(Path dbFile) {
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            Path path = dbFile.resolveSibling(dbFile.getFileName() + suffix);
            if (Files.exists(path)) {
                FileSecurity.hardenFile(path);
            }
        }
    }
}
