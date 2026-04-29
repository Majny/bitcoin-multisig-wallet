package cz.majny.wallet.registry

import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database

/* Database bootstrap for wallet-registry. Runs Flyway migrations then
 * connects Exposed through a Hikari pool. Matches auth-service layout. */
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

    /* Migrate then connect - Exposed always sees a complete schema. */
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
            maximumPoolSize = 10
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
        }

        val ds = HikariDataSource(hikariCfg)

        Database.connect(ds)
    }
}
