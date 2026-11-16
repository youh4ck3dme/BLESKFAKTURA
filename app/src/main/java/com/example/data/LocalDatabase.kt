package com.example.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "invoices")
data class InvoiceEntity(
    @PrimaryKey val id: String,
    val supplierName: String,
    val ico: String?,
    val dic: String?,
    val issueDate: String,
    val dueDate: String?,
    val isPaid: Boolean,
    val currency: String,
    val totalAmount: Double,
    val totalAmountCents: Long,
    val vatAmount: Double?,
    val vatAmountCents: Long?,
    val vatRate: Double?,
    val category: String,
    val description: String?,
    val imageUrl: String?,
    val userId: String,
    val createdAt: Long
) {
    fun toInvoice(): Invoice {
        val cents = if (totalAmountCents > 0L) totalAmountCents else SlovakFinanceUtils.doubleToCents(totalAmount)
        val vatCents = vatAmountCents ?: vatAmount?.let { SlovakFinanceUtils.doubleToCents(it) }
        return Invoice(
            id = id,
            supplierName = supplierName,
            ico = ico,
            dic = dic,
            issueDate = issueDate,
            dueDate = dueDate,
            isPaid = isPaid,
            currency = currency.ifBlank { "EUR" },
            totalAmount = SlovakFinanceUtils.centsToDouble(cents),
            totalAmountCents = cents,
            vatAmount = vatCents?.let { SlovakFinanceUtils.centsToDouble(it) },
            vatAmountCents = vatCents,
            vatRate = vatRate,
            category = category,
            description = description,
            imageUrl = imageUrl,
            userId = userId,
            createdAt = createdAt
        )
    }
}

fun Invoice.toEntity(): InvoiceEntity {
    val cents = effectiveTotalCents()
    val vatCents = effectiveVatCents()
    return InvoiceEntity(
        id = id,
        supplierName = supplierName,
        ico = ico,
        dic = dic,
        issueDate = issueDate,
        dueDate = dueDate,
        isPaid = isPaid,
        currency = currency.ifBlank { "EUR" },
        totalAmount = SlovakFinanceUtils.centsToDouble(cents),
        totalAmountCents = cents,
        vatAmount = vatCents?.let { SlovakFinanceUtils.centsToDouble(it) },
        vatAmountCents = vatCents,
        vatRate = vatRate,
        category = category,
        description = description,
        imageUrl = imageUrl,
        userId = userId,
        createdAt = createdAt
    )
}

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val userId: String,
    val credits: Int,
    val isSubscribed: Boolean,
    val totalInvoiced: Double,
    val totalInvoicedCents: Long,
    val invoiceCount: Int
) {
    fun toUserStats(): UserStats {
        val cents = if (totalInvoicedCents > 0L) totalInvoicedCents else SlovakFinanceUtils.doubleToCents(totalInvoiced)
        return UserStats(
            userId = userId,
            credits = credits,
            isSubscribed = isSubscribed,
            totalInvoiced = SlovakFinanceUtils.centsToDouble(cents),
            totalInvoicedCents = cents,
            invoiceCount = invoiceCount
        )
    }
}

fun UserStats.toEntity(): UserStatsEntity {
    val cents = effectiveTotalInvoicedCents()
    return UserStatsEntity(
        userId = userId,
        credits = credits,
        isSubscribed = isSubscribed,
        totalInvoiced = SlovakFinanceUtils.centsToDouble(cents),
        totalInvoicedCents = cents,
        invoiceCount = invoiceCount
    )
}

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices WHERE userId = :userId ORDER BY createdAt DESC")
    fun getInvoicesForUser(userId: String): Flow<List<InvoiceEntity>>

    @Query("SELECT * FROM invoices WHERE userId = :userId ORDER BY createdAt DESC")
    suspend fun getInvoicesForUserOnce(userId: String): List<InvoiceEntity>

    @Query("SELECT * FROM invoices WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getInvoiceByIdForUser(id: String, userId: String): InvoiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertInvoice(invoice: InvoiceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(invoices: List<InvoiceEntity>)

    @Query("DELETE FROM invoices WHERE id = :id AND userId = :userId")
    suspend fun deleteInvoiceForUser(id: String, userId: String)

    @Query("DELETE FROM invoices WHERE userId = :userId")
    suspend fun deleteAllForUser(userId: String)
}

@Dao
interface UserStatsDao {
    @Query("SELECT * FROM user_stats WHERE userId = :userId LIMIT 1")
    suspend fun getUserStats(userId: String): UserStatsEntity?

    @Query("SELECT * FROM user_stats WHERE userId = :userId LIMIT 1")
    fun getUserStatsFlow(userId: String): Flow<UserStatsEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUserStats(stats: UserStatsEntity)

    @Query("DELETE FROM user_stats WHERE userId = :userId")
    suspend fun deleteUserStats(userId: String)
}

@Database(
    entities = [InvoiceEntity::class, UserStatsEntity::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun invoiceDao(): InvoiceDao
    abstract fun userStatsDao(): UserStatsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE invoices ADD COLUMN dueDate TEXT")
                db.execSQL("ALTER TABLE invoices ADD COLUMN isPaid INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN currency TEXT NOT NULL DEFAULT 'EUR'")
                db.execSQL("ALTER TABLE invoices ADD COLUMN totalAmountCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE invoices ADD COLUMN vatAmountCents INTEGER")
                db.execSQL("UPDATE invoices SET totalAmountCents = CAST(ROUND(totalAmount * 100.0) AS INTEGER)")
                db.execSQL("UPDATE invoices SET vatAmountCents = CASE WHEN vatAmount IS NOT NULL THEN CAST(ROUND(vatAmount * 100.0) AS INTEGER) ELSE NULL END")

                db.execSQL("ALTER TABLE user_stats ADD COLUMN totalInvoicedCents INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE user_stats SET totalInvoicedCents = CAST(ROUND(totalInvoiced * 100.0) AS INTEGER)")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "bleskova_faktura_db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
