package org.panda.stuff

import play.api._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import play.api.libs.json.{JsValue, JsObject, JsString, JsArray}
import play.api.Play.current
import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import scala.concurrent.duration._
import scala.concurrent.Future

case class JoinX(username: String)
case class Quit(username: String)
case class Talk(username: String, text: String)
case class NotifyJoin(username: String)
case class ConnectedX(enumerator:Enumerator[JsValue])
case class CannotConnectX(msg: String)

object Robot {

    def apply(liveUpdate: ActorRef) {

        // Create an Iteratee that log all messages to the console.
        val loggerIteratee = Iteratee.foreach[JsValue](event => Logger("robot").info(event.toString))

        implicit val timeout = Timeout(1 second)
        // Make the robot join the room
        liveUpdate ? (JoinX("Robot")) map {
          case ConnectedX(robotChannel) =>
            // Apply this Enumerator on the logger.
            robotChannel |>> loggerIteratee
        }

        // Make the robot talk every 30 seconds
        Akka.system.scheduler.schedule(
            30 seconds,
            30 seconds,
            liveUpdate,
            Talk("Robot", "I'm still alive")
        )
    }

}

class LiveUpdate extends Actor {
    var members = Set.empty[String]
    val (chatEnumerator, chatChannel) = Concurrent.broadcast[JsValue]

    def receive = {
        case JoinX(username) => {
            // Create an Enumerator to write to this socket
            if (members.contains(username)) {
                sender ! CannotConnectX("This username is already used")
            } else {
                members = members + username
                sender ! ConnectedX(chatEnumerator)
                self ! NotifyJoin(username)
            }
        }
        case NotifyJoin(username) => {
            notifyAll("join", username, "has entered the room")
        }

        case Talk(username, text) => {
            notifyAll("talk", username, text)
        }

        case Quit(username) => {
            members = members - username
            notifyAll("quit", username, "has left the room")
        }
    }

    def notifyAll(kind: String, user: String, text: String) {
        val msg = JsObject(
            Seq(
                "kind" -> JsString(kind),
                "user" -> JsString(user),
                "message" -> JsString(text),
                "members" -> JsArray(members.toList.map(JsString))
            )
        )
        chatChannel.push(msg)
    }
}

object LiveUpdate {
    implicit val timeout = Timeout(1 second)

    lazy val default = {
        val roomActor = Akka.system.actorOf(Props[LiveUpdate])
        Robot(roomActor)
        roomActor
    }

    def join (username: String) : Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
        (default ? JoinX(username)).map {
            case ConnectedX(enumerator) =>

                // Create an Iteratee to consume the feed
                val iteratee = Iteratee.foreach[JsValue] { event =>
                  default ! Talk(username, (event \ "text").as[String])
                }.mapDone { _ =>
                  default ! Quit(username)
                }

                (iteratee,enumerator)

            case CannotConnectX(error) =>

                // Connection error

                // A finished Iteratee sending EOF
                val iteratee = Done[JsValue,Unit]((),Input.EOF)

                // Send an error and close the socket
                val enumerator =  Enumerator[JsValue](JsObject(Seq("error" -> JsString(error)))).andThen(Enumerator.enumInput(Input.EOF))

                (iteratee,enumerator)
        }
    }
}
