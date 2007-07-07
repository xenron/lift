package net.liftweb.mapper

/*                                                *\
 (c) 2006-2007 WorldWide Conferencing, LLC
 Distributed under an Apache License
 http://www.apache.org/licenses/LICENSE-2.0
 \*                                                 */

import java.sql.{Connection, ResultSet, Statement, PreparedStatement}
import javax.sql.{ DataSource}
import javax.naming.{Context, InitialContext}
import scala.collection.mutable._
import net.liftweb.util._
import net.liftweb.util.Lazy._

object DB {
  private val threadStore = new ThreadLocal
  private val envContext = Lazy((new InitialContext).lookup("java:/comp/env").asInstanceOf[Context])
  val logger = Log.logger(DB.getClass)
  
  var queryTimeout: Option[Int] = None
  
  /**
    * can we get a JDBC connection from JNDI?
    */
  def jndiJdbcConnAvailable_? : boolean = {
    val touchedEnv = envContext.calculated_?
    
    val ret = try {
      (envContext.get.lookup(DefaultConnectionIdentifier.jndiName).asInstanceOf[DataSource].getConnection) != null
    } catch {
      case e => false
    }
        
    if (!touchedEnv) envContext.reset
    ret
  }
  
  // var connectionManager: Option[ConnectionManager] = None
  private val connectionManagers = new HashMap[ConnectionIdentifier, ConnectionManager];
  
  def defineConnectionManager(name: ConnectionIdentifier, mgr: ConnectionManager) {
    connectionManagers(name) = mgr
  }
  
  private def info : HashMap[ConnectionIdentifier, (SuperConnection, Int)] = {
    threadStore.get match {
      case null =>
	val tinfo = new HashMap[ConnectionIdentifier, (SuperConnection, Int)];
	threadStore.set(tinfo)
	tinfo

      case v: HashMap[ConnectionIdentifier, (SuperConnection, Int)] => v
    }
  }
  

  private def newConnection(name : ConnectionIdentifier) : SuperConnection = {
    val ret = (connectionManagers.get(name) match {
      case Some(cm) => cm.newConnection(name).map(c => new SuperConnection(c, () => cm.releaseConnection(c)))
      case _ => None
    }) getOrElse {
      val conn = envContext.get.lookup(name.jndiName).asInstanceOf[DataSource].getConnection
      new SuperConnection(conn, () => conn.close)
    }
    ret.setAutoCommit(false)
    ret
  }
  
  
  private def releaseConnection(conn : SuperConnection) : unit = conn.close
  
  private def getConnection(name : ConnectionIdentifier): SuperConnection =  {
    var ret = info.get(name) match {
      case None => (newConnection(name), 1)
      case Some(c) => (c._1, c._2 + 1)
    }
    info(name) = ret
    ret._1
  }
  
  def releaseConnectionNamed(name : ConnectionIdentifier) {
    info.get(name) match {
      case Some((c, 1)) => c.commit; c.releaseFunc() ; info -= name; logger.trace("Released connection "+name)
      case Some((c, n)) => info(name) = (c, n - 1)
      case _ =>
    }
  }
  
  def statement[T](db : SuperConnection)(f : (Statement) => T) : T =  {
    val st = db.createStatement
    queryTimeout.foreach(to => st.setQueryTimeout(to))
    try {
      f(st)
    } finally {
      st.close
    }
  }
  
  def exec[T](db : SuperConnection, query : String)(f : (ResultSet) => T) : T = {
    statement(db) {st => 
      logger.trace("About to execute query: "+query)
      f(st.executeQuery(query))
      }
  }
  
  def exec[T](statement : PreparedStatement)(f : (ResultSet) => T) : T = {
    queryTimeout.foreach(to => statement.setQueryTimeout(to))
    val rs = statement.executeQuery
    try {
      f(rs)
    } finally {
      rs.close
    }
  }
  
  def prepareStatement[T](statement : String, conn: SuperConnection)(f : (PreparedStatement) => T) : T = {
    logger.trace("About to prepare statement "+statement)
    val st = conn.prepareStatement(statement)
    queryTimeout.foreach(to => st.setQueryTimeout(to))
      try {
	f(st)
      } finally {
        st.close
      }
  }
  
  def prepareStatement[T](statement : String,keys: int, conn: SuperConnection)(f : (PreparedStatement) => T) : T = {
    logger.trace("About to prepare statement: "+statement+" with keys "+keys)
        val st = conn.prepareStatement(statement, keys)
        queryTimeout.foreach(to => st.setQueryTimeout(to))
      try {
        f(st)
      } finally {
        st.close
      }
  }
  
  def use[T](name : ConnectionIdentifier)(f : (SuperConnection) => T) : T = {
    val conn = getConnection(name)
    try {
      f(conn)
    } finally {
      releaseConnectionNamed(name)
    }
  }
}


abstract class DriverType
object MySqlDriver extends DriverType
object DerbyDriver extends DriverType


class SuperConnection(val connection: Connection,val releaseFunc: () => Any) {
  val brokenLimit_? : Lazy[boolean] = Lazy(connection.getMetaData.getDatabaseProductName ==  "Apache Derby")
  def createTablePostpend: String = driverType match {
    case DerbyDriver => ""
    case MySqlDriver => " ENGINE = InnoDB "
  }
  
  def supportsForeignKeys_? : Boolean = driverType match {
    case DerbyDriver => false
    case MySqlDriver => false
  }
  
  private val _driverType = Lazy(calcDriver(connection.getMetaData.getDatabaseProductName))
  
  def driverType = _driverType.get
    
  def calcDriver(name: String): DriverType = {
    name match {
      case "Apache Derby" => DerbyDriver
      case "MySQL" => MySqlDriver
    }
  }  
}

object SuperConnection {
  implicit def superToConn(in: SuperConnection): Connection = in.connection
}

trait ConnectionIdentifier {
  def jndiName: String
}

case object DefaultConnectionIdentifier extends ConnectionIdentifier {
  var jndiName = "lift"
}
