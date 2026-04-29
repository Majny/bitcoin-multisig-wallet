package cz.majny.wallet.authservice

import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/* Database bootstrap. Reads connection settings via Typesafe Config (so docker
 * env can override application.conf), runs Flyway migrations and then starts
 * a Hikari pool wired into Exposed. */
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

    /* Migrate first, then connect - guarantees Exposed never sees a half-applied schema. */
    fun init(db: DbConfig) {
        Flyway.configure()
            .dataSource(db.url, db.user, db.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val hikariCfg = HikariConfig().apply {
            jdbcUrl = db.url
            username = db.user
            password = db.password
            maximumPoolSize = 5
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        Database.connect(HikariDataSource(hikariCfg))
    }
}
