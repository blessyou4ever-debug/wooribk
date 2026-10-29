package com.woori.gyeongjobi.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun fromStatus(value: RecordStatus): String = value.name

    @TypeConverter
    fun toStatus(value: String): RecordStatus = RecordStatus.fromString(value)

    @TypeConverter
    fun fromEventType(value: EventType): String = value.name

    @TypeConverter
    fun toEventType(value: String): EventType = EventType.fromLabelOrName(value)

    @TypeConverter
    fun fromRelationType(value: RelationType): String = value.name

    @TypeConverter
    fun toRelationType(value: String): RelationType = RelationType.fromLabelOrName(value)

    @TypeConverter
    fun fromSource(value: RecordSource): String = value.name

    @TypeConverter
    fun toSource(value: String): RecordSource =
        RecordSource.entries.find { it.name == value } ?: RecordSource.NOTIFICATION
}

@Database(entities = [ExpenseEntity::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun expenseDao(): ExpenseDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "woori_gyeongjobi.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
