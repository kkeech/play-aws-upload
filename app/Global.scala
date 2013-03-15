import play.api.db.DB
import play.api.GlobalSettings
import play.api.Application
import play.api.Play.current

import scala.slick.driver.H2Driver.simple._
import Database.threadLocalSession

import org.panda.models._

object Global extends GlobalSettings {
    override def onStart (app: Application) {
        lazy val database = Database.forDataSource(DB.getDataSource())

        play.api.Logger.info("onStart")

        database withSession {
            // Create the database tables
            FileUploadProgressT.ddl.create
            TranscodeProgressT.ddl.create
        }
    }
}
