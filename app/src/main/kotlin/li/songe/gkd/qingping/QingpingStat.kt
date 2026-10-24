package li.songe.gkd.qingping

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "qingping_stat", primaryKeys = ["app_id", "kind"])
data class QingpingStat(
    @ColumnInfo(name = "app_id") val appId: String,
    val kind: String,
    val count: Long = 0,
    @ColumnInfo(name = "last_time") val lastTime: Long = 0,
)

@Dao
interface QingpingStatDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun create(row: QingpingStat)

    @Query("UPDATE qingping_stat SET count=count+1, last_time=:time WHERE app_id=:appId AND kind=:kind")
    suspend fun increment(appId: String, kind: String, time: Long)

    @Transaction
    suspend fun record(appId: String, kind: String, time: Long) {
        create(QingpingStat(appId, kind))
        increment(appId, kind, time)
    }

    @Query("SELECT * FROM qingping_stat ORDER BY count DESC, app_id ASC, kind ASC")
    fun observe(): Flow<List<QingpingStat>>

    @Query("SELECT * FROM qingping_stat WHERE app_id=:appId AND kind=:kind")
    suspend fun get(appId: String, kind: String): QingpingStat?
}
