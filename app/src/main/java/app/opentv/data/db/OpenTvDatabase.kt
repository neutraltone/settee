/*
 * This file is part of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.opentv.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import app.opentv.data.model.Category
import app.opentv.data.model.Channel
import app.opentv.data.model.ChannelHiddenChange
import app.opentv.data.model.EpgChannelAlias
import app.opentv.data.model.EpgFeed
import app.opentv.data.model.Episode
import app.opentv.data.model.Favourite
import app.opentv.data.model.LivePlayback
import app.opentv.data.model.LiveStreamFormat
import app.opentv.data.model.Movie
import app.opentv.data.model.MovieCredit
import app.opentv.data.model.PlaybackPosition
import app.opentv.data.model.Profile
import app.opentv.data.model.ProfileSetting
import app.opentv.data.model.Programme
import app.opentv.data.model.RecentSearch
import app.opentv.data.model.Recording
import app.opentv.data.model.RecordingStatus
import app.opentv.data.model.Reminder
import app.opentv.data.model.Series
import app.opentv.data.model.SeriesCredit
import app.opentv.data.model.SeriesDismissal
import app.opentv.data.model.SeriesRule
import app.opentv.data.model.Source
import app.opentv.data.model.SourceKind
import app.opentv.data.model.StreamKind

class Converters {
    @TypeConverter fun sourceKindToString(value: SourceKind): String = value.name

    @TypeConverter fun stringToSourceKind(value: String): SourceKind =
        runCatching { SourceKind.valueOf(value) }.getOrDefault(SourceKind.M3U)

    @TypeConverter fun liveStreamFormatToString(value: LiveStreamFormat): String = value.name

    @TypeConverter fun stringToLiveStreamFormat(value: String): LiveStreamFormat =
        runCatching { LiveStreamFormat.valueOf(value) }.getOrDefault(LiveStreamFormat.HLS)

    @TypeConverter fun streamKindToString(value: StreamKind): String = value.name

    @TypeConverter fun stringToStreamKind(value: String): StreamKind =
        runCatching { StreamKind.valueOf(value) }.getOrDefault(StreamKind.LIVE)

    @TypeConverter fun recordingStatusToString(value: RecordingStatus): String = value.name

    @TypeConverter fun stringToRecordingStatus(value: String): RecordingStatus =
        runCatching { RecordingStatus.valueOf(value) }.getOrDefault(RecordingStatus.FAILED)
}

@Database(
    entities = [
        Source::class,
        Category::class,
        Channel::class,
        ChannelHiddenChange::class,
        EpgFeed::class,
        EpgChannelAlias::class,
        Programme::class,
        Movie::class,
        MovieCredit::class,
        Series::class,
        SeriesCredit::class,
        Episode::class,
        PlaybackPosition::class,
        SeriesDismissal::class,
        Favourite::class,
        LivePlayback::class,
        RecentSearch::class,
        Profile::class,
        ProfileSetting::class,
        Recording::class,
        SeriesRule::class,
        Reminder::class,
    ],
    version = 11,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class OpenTvDatabase : RoomDatabase() {
    abstract fun sources(): SourceDao
    abstract fun categories(): CategoryDao
    abstract fun channels(): ChannelDao
    abstract fun epgFeeds(): EpgFeedDao
    abstract fun epgAliases(): EpgChannelAliasDao
    abstract fun programmes(): ProgrammeDao
    abstract fun movies(): MovieDao
    abstract fun series(): SeriesDao
    abstract fun episodes(): EpisodeDao
    abstract fun positions(): PlaybackPositionDao
    abstract fun seriesDismissals(): SeriesDismissalDao
    abstract fun favourites(): FavouriteDao
    abstract fun livePlayback(): LivePlaybackDao
    abstract fun recentSearches(): RecentSearchDao
    abstract fun profiles(): ProfileDao
    abstract fun profileSettings(): ProfileSettingDao
    abstract fun recordings(): RecordingDao
    abstract fun seriesRules(): SeriesRuleDao
    abstract fun reminders(): ReminderDao

    companion object {
        const val FILE_NAME = "opentv.db"

        /**
         * 9 → 10: adds the credits a person's page looks people up in, a film's writers and studios,
         * and marks every film as not yet looked up, so the background pass asks each once for the cast
         * version 9 threw away, and TMDB for the rest.
         * Nothing is dropped, so it's worth not wiping providers for. The credits start empty:
         * `CatalogRepository.creditEarlierTitles` fills them in for the titles already saved, which
         * needs Kotlin to fold the names.
         */
        private val CREDITS = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `movie_credits` (`movieId` INTEGER NOT NULL, " +
                        "`role` TEXT NOT NULL, `nameKey` TEXT NOT NULL, " +
                        "PRIMARY KEY(`movieId`, `role`, `nameKey`), FOREIGN KEY(`movieId`) " +
                        "REFERENCES `movies`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_movie_credits_nameKey` ON `movie_credits` (`nameKey`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `series_credits` (`seriesId` INTEGER NOT NULL, " +
                        "`nameKey` TEXT NOT NULL, PRIMARY KEY(`seriesId`, `nameKey`), FOREIGN KEY(`seriesId`) " +
                        "REFERENCES `series`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_series_credits_nameKey` ON `series_credits` (`nameKey`)")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `detailsLookedUp` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `tmdbLookedUp` INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `writer` TEXT")
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `studio` TEXT")
                // A provider's director field is its whole directing crew; only the first is the
                // director (see `providerDirector`). Commas only: no panel has been seen using | or ;.
                db.execSQL(
                    "UPDATE `movies` SET `director` = TRIM(SUBSTR(`director`, 1, INSTR(`director`, ',') - 1)) " +
                        "WHERE INSTR(`director`, ',') > 1",
                )
            }
        }

        /**
         * 10 → 11: when TMDB last answered about each film and series, so the background pass asks
         * again six months on. Films TMDB has already answered about are dated now, the best guess
         * there is, so they come round in six months rather than all being asked again at once.
         */
        private val TMDB_ANSWERED_AT = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `movies` ADD COLUMN `tmdbAnsweredAt` INTEGER")
                db.execSQL("ALTER TABLE `series` ADD COLUMN `tmdbAnsweredAt` INTEGER")
                db.execSQL(
                    "UPDATE `movies` SET `tmdbAnsweredAt` = CAST(strftime('%s', 'now') AS INTEGER) * 1000 " +
                        "WHERE `tmdbLookedUp` = 1",
                )
            }
        }

        /**
         * Every migration, in order. Shared with the migration test so neither list can lag. None
         * before 9, which wiped under the pre-1.0 policy below; its older schemas went with it.
         */
        internal val MIGRATIONS: Array<Migration> = arrayOf(CREDITS, TMDB_ANSWERED_AT)

        /**
         * [onWiped] runs when the destructive fallback below rebuilds the database. Settings that
         * hold a row id (active profile, last channel) would otherwise outlive their rows and
         * later match whatever reuses that id.
         */
        fun build(context: Context, onWiped: () -> Unit = {}): OpenTvDatabase =
            Room.databaseBuilder(context, OpenTvDatabase::class.java, FILE_NAME)
                // WAL keeps guide writes from blocking guide reads, so a background EPG
                // refresh cannot make the UI stutter on a slow TV box.
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(*MIGRATIONS)
                /*
                 * Pre-1.0 policy: until 1.0, schema changes drop and rebuild the database.
                 * Everything in it is re-derivable from the provider (one sync away) except
                 * favourites and overrides, and only the developer installs 0.x releases. From
                 * 1.0 every schema change ships a Migration and this line is deleted.
                 *
                 * It also covers downgrades, which matters because the schema restarted at v1: a
                 * device that ran a build from before the restart holds an upstream v11 database.
                 */
                .fallbackToDestructiveMigration()
                .addCallback(
                    object : Callback() {
                        override fun onDestructiveMigration(db: SupportSQLiteDatabase) = onWiped()
                    },
                )
                .build()
    }
}
