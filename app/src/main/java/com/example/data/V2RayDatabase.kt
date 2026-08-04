package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ServerEntity::class, SubscriptionEntity::class, LogEntity::class],
    version = 4,
    exportSchema = false
)
abstract class V2RayDatabase : RoomDatabase() {
    abstract fun serverDao(): ServerDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun logDao(): LogDao

    companion object {
        @Volatile
        private var INSTANCE: V2RayDatabase? = null

        // v2 -> v3: add pinnedCert column for TLS cert pinning (pcs param).
        // ALTER TABLE keeps ALL existing data intact.
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN pinnedCert TEXT NOT NULL DEFAULT ''")
            }
        }

        // v3 -> v4: transport/detailing columns (spiderX, alpn, headerType, grpcServiceName).
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE servers ADD COLUMN spiderX TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE servers ADD COLUMN alpn TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE servers ADD COLUMN headerType TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE servers ADD COLUMN grpcServiceName TEXT NOT NULL DEFAULT ''")
            }
        }

        fun getDatabase(context: Context): V2RayDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    V2RayDatabase::class.java,
                    "v2ray_dan_database"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
