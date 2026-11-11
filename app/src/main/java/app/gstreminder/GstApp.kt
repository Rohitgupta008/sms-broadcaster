package app.gstreminder

import android.app.Application
import android.content.Context
import app.gstreminder.data.AppDb
import app.gstreminder.data.Repository
import app.gstreminder.data.Settings
import app.gstreminder.work.Scheduler

class GstApp : Application() {
    val repository: Repository by lazy { Repository(AppDb.create(this), Settings(this)) }

    override fun onCreate() {
        super.onCreate()
        Scheduler.scheduleDaily(this, repository.settings, replace = false)
    }
}

val Context.repo: Repository get() = (applicationContext as GstApp).repository
