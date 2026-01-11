package cz.majny.wallet.registry

import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

object Db {

    data class DbConfig(
        val url: String,
        val user: String,
        val password: String
    )

    fun loadConfig(): DbConfig {
        val cfg = ConfigFactory.load()
        return DbConfig(
            url = cfg.getString("db.url"),
            user = cfg.getString("db.user"),
            password = cfg.getString("db.password")
        )
    }

    fun init(db: DbConfig) {

        // migration
        Flyway.configure()
            .dataSource(db.url, db.user, db.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        // connection pool
        val hikariCfg = HikariConfig().apply {
            jdbcUrl = db.url
            username = db.user
            password = db.password
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        val ds = HikariDataSource(hikariCfg)

        Database.connect(ds)
    }
}
