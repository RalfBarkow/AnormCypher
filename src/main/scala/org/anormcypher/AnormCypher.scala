package org.anormcypher

import MayErr._
import scala.concurrent._, duration._
import scala.reflect.ClassTag

abstract class CypherRequestError

case class ColumnNotFound(columnName: String, possibilities: List[String]) extends CypherRequestError {
  override def toString = columnName + " not found, available columns : " + possibilities.map {
    p => p.dropWhile(_ == '.')
  }.mkString(", ")
}

case class TypeDoesNotMatch(message: String) extends CypherRequestError
case class InnerTypeDoesNotMatch(message: String) extends CypherRequestError
case class UnexpectedNullableFound(on: String) extends CypherRequestError
case object NoColumnsInReturnedResult extends CypherRequestError
case class CypherMappingError(msg: String) extends CypherRequestError

trait Column[A] extends ((Any, MetaDataItem) => MayErr[CypherRequestError, A])

object Column {

  def apply[A](transformer: ((Any, MetaDataItem) => MayErr[CypherRequestError, A])): Column[A] = new Column[A] {

    def apply(value: Any, meta: MetaDataItem): MayErr[CypherRequestError, A] = transformer(value, meta)

  }

  def nonNull[A](transformer: ((Any, MetaDataItem) => MayErr[CypherRequestError, A])): Column[A] = Column[A] {
    case (value, meta@MetaDataItem(qualified, _, _)) =>
      if (value != null) transformer(value, meta) else Left(UnexpectedNullableFound(qualified.toString))
  }

  implicit def rowToString = Column.nonNull[String] {
    (value, meta) => value match {
      case s: String => Right(s)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to String for column ${meta.column}"))
    }
  }

  implicit def rowToInt = Column.nonNull[Int] {
    (value, meta) => value match {
      case bd: BigDecimal if bd.isValidInt => Right(bd.toIntExact)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Int for column ${meta.column}"))
    }
  }

  implicit def rowToDouble = Column.nonNull[Double] {
    (value, meta) => value match {
      case bd: BigDecimal => Right(bd.toDouble)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to Double for column ${meta.column}"))
    }
  }

  implicit def rowToFloat = Column.nonNull[Float] {
    (value, meta) => value match {
      case bd: BigDecimal => Right(bd.toFloat)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to Float for column ${meta.column}"))
    }
  }

  implicit def rowToShort = Column.nonNull[Short] {
    (value, meta) => value match {
      case bd: BigDecimal if bd.isValidShort => Right(bd.toShortExact)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to Short for column ${meta.column}"))
    }
  }

  implicit def rowToByte = Column.nonNull[Byte] {
    (value, meta) => value match {
      case bd: BigDecimal if bd.isValidByte => Right(bd.toByteExact)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to Byte for column ${meta.column}"))
    }
  }

  implicit def rowToBoolean = Column.nonNull[Boolean] {
    (value, meta) => value match {
      case b: Boolean => Right(b)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Boolean for column ${meta.column}"))
    }
  }

  implicit def rowToLong = Column.nonNull[Long] {
    (value, meta) => value match {
      case bd: BigDecimal if bd.isValidLong => Right(bd.toLongExact)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to Long for column ${meta.column}"))
    }
  }

  implicit def rowToNeoNode = Column.nonNull[NeoNode] {
    (value, meta) => value match {
      case msa: Map[_, _] if msa.keys.forall(_.isInstanceOf[String]) =>
        Neo4jREST.asNode(msa.asInstanceOf[Map[String, Any]])
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to NeoNode for column ${meta.column}"))
    }
  }

  implicit def rowToNeoRelationship: Column[NeoRelationship] = Column.nonNull {
    (value, meta) => value match {
      case msa: Map[_, _] if msa.keys.forall(_.isInstanceOf[String]) =>
        Neo4jREST.asRelationship(msa.asInstanceOf[Map[String, Any]])
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to NeoRelationship for column ${meta.column}"))
    }
  }

  implicit def rowToBigInt = Column.nonNull[BigInt] {
    (value, meta) => value match {
      case bd: BigDecimal => bd.toBigIntExact() match {
        case Some(bi) => Right(bi)
        case None => Left(TypeDoesNotMatch(s"Cannot convert $bd:${bd.getClass} to BigInt for column ${meta.column}"))
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x:${x.getClass} to BigInt for column ${meta.column}"))
    }
  }

  implicit def rowToBigDecimal = Column.nonNull[BigDecimal] {
    (value, meta) => value match {
      case b: BigDecimal => Right(b)
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to BigDecimal for column ${meta.column}"))
    }
  }

  implicit def rowToOption[A, B](implicit transformer: Column[A]): Column[Option[A]] = Column {
    (value, meta) => if (value != null)
      transformer(value, meta).map(Some(_))
    else
      (Right(None): MayErr[CypherRequestError, Option[A]])
  }

  import scala.util.control.Exception.allCatch

  def checkSeq[A : ClassTag](seq: Seq[Any], meta: MetaDataItem)(mapFun: (Any) => A): MayErr[CypherRequestError, Seq[A]] = {
    allCatch.either {
      seq.map(mapFun)
    } fold(
      throwable => Left(InnerTypeDoesNotMatch(s"Cannot convert $seq:${seq.getClass} to " +
        s"Seq[${implicitly[ClassTag[A]].runtimeClass}] for column ${meta.column}: ${throwable.getLocalizedMessage}")),
      result => Right(result)
      )
  }

  implicit def rowToSeqString = Column.nonNull[Seq[String]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[String](xs, meta) {
        case s: String => s
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to String")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[String] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqInt = Column.nonNull[Seq[Int]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[Int](xs, meta) {
        case bd: BigDecimal if bd.isValidInt => bd.toIntExact
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to Int")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[Int] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqLong = Column.nonNull[Seq[Long]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[Long](xs, meta) {
        case bd: BigDecimal if bd.isValidLong => bd.toLongExact
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to Long")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[Long] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqBoolean = Column.nonNull[Seq[Boolean]] {
    (value, meta) => value match {
      case xs: Seq[_] => Column.checkSeq[Boolean](xs, meta) {
        case b: Boolean => b
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to Boolean")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[Boolean] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqDouble = Column.nonNull[Seq[Double]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[Double](xs, meta) {
        case bd: BigDecimal => bd.toDouble
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to Double")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[Double] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqNeoRelationship = Column.nonNull[Seq[NeoRelationship]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[NeoRelationship](xs, meta) {
        case msa: Map[_,_] if msa.keys.forall(_.isInstanceOf[String]) => {
          Neo4jREST.asRelationship(msa.asInstanceOf[Map[String, Any]]) fold(
            error => throw new RuntimeException(error match {
              case TypeDoesNotMatch(msg) => msg
              case _ => ""
            }),
            value => value
            )
        }
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to NeoRelationship")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[NeoRelationship] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqNeoNode: Column[Seq[NeoNode]] = Column.nonNull {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[NeoNode](xs, meta) {
        case msa: Map[_,_] if msa.keys.forall(_.isInstanceOf[String]) => {
          Neo4jREST.asNode(msa.asInstanceOf[Map[String, Any]]) fold(
            error => throw new RuntimeException(error match {
              case TypeDoesNotMatch(msg) => msg
              case _ => ""
            }),
            value => value
            )
        }
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to NeoNode")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[NeoNode] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqMapStringString = Column.nonNull[Seq[Map[String,String]]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[Map[String,String]](xs, meta) {
        case m: Map[String,String] => m
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to Map[String,String]")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[Map[String,String]] for column ${meta.column}"))
    }
  }

  implicit def rowToSeqMapStringAny = Column.nonNull[Seq[Map[String,Any]]] {
    (value, meta) => value match {
      case xs: Seq[_] => checkSeq[Map[String,Any]](xs, meta) {
        case m: Map[String,Any] => m
        case x => throw new RuntimeException(s"Cannot convert $x: ${x.getClass} to Map[String,Any]")
      }
      case x => Left(TypeDoesNotMatch(s"Cannot convert $x: ${x.getClass} to Seq[Map[String,Any]] for column ${meta.column}"))
    }
  }

}

case class TupleFlattener[F](f: F)

trait PriorityOne {
  implicit def flattenerTo2[T1, T2]: TupleFlattener[(T1 ~ T2) => (T1, T2)] = TupleFlattener[(T1 ~ T2) => (T1, T2)] { case (t1 ~ t2) => (t1, t2) }
}

trait PriorityTwo extends PriorityOne {
  implicit def flattenerTo3[T1, T2, T3]: TupleFlattener[(T1 ~ T2 ~ T3) => (T1, T2, T3)] = TupleFlattener[(T1 ~ T2 ~ T3) => (T1, T2, T3)] { case (t1 ~ t2 ~ t3) => (t1, t2, t3) }
}

trait PriorityThree extends PriorityTwo {
  implicit def flattenerTo4[T1, T2, T3, T4]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4) => (T1, T2, T3, T4)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4) => (T1, T2, T3, T4)] { case (t1 ~ t2 ~ t3 ~ t4) => (t1, t2, t3, t4) }
}

trait PriorityFour extends PriorityThree {
  implicit def flattenerTo5[T1, T2, T3, T4, T5]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5) => (T1, T2, T3, T4, T5)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5) => (T1, T2, T3, T4, T5)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5) => (t1, t2, t3, t4, t5) }
}

trait PriorityFive extends PriorityFour {
  implicit def flattenerTo6[T1, T2, T3, T4, T5, T6]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6) => (T1, T2, T3, T4, T5, T6)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6) => (T1, T2, T3, T4, T5, T6)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5 ~ t6) => (t1, t2, t3, t4, t5, t6) }
}

trait PrioritySix extends PriorityFive {
  implicit def flattenerTo7[T1, T2, T3, T4, T5, T6, T7]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7) => (T1, T2, T3, T4, T5, T6, T7)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7) => (T1, T2, T3, T4, T5, T6, T7)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5 ~ t6 ~ t7) => (t1, t2, t3, t4, t5, t6, t7) }
}

trait PrioritySeven extends PrioritySix {
  implicit def flattenerTo8[T1, T2, T3, T4, T5, T6, T7, T8]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8) => (T1, T2, T3, T4, T5, T6, T7, T8)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8) => (T1, T2, T3, T4, T5, T6, T7, T8)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5 ~ t6 ~ t7 ~ t8) => (t1, t2, t3, t4, t5, t6, t7, t8) }
}

trait PriorityEight extends PrioritySeven {
  implicit def flattenerTo9[T1, T2, T3, T4, T5, T6, T7, T8, T9]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8 ~ T9) => (T1, T2, T3, T4, T5, T6, T7, T8, T9)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8 ~ T9) => (T1, T2, T3, T4, T5, T6, T7, T8, T9)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5 ~ t6 ~ t7 ~ t8 ~ t9) => (t1, t2, t3, t4, t5, t6, t7, t8, t9) }
}

trait PriorityNine extends PriorityEight {
  implicit def flattenerTo10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8 ~ T9 ~ T10) => (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8 ~ T9 ~ T10) => (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5 ~ t6 ~ t7 ~ t8 ~ t9 ~ t10) => (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10) }
}

object TupleFlattener extends PriorityNine {
  implicit def flattenerTo11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11]: TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8 ~ T9 ~ T10 ~ T11) => (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)] = TupleFlattener[(T1 ~ T2 ~ T3 ~ T4 ~ T5 ~ T6 ~ T7 ~ T8 ~ T9 ~ T10 ~ T11) => (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11)] { case (t1 ~ t2 ~ t3 ~ t4 ~ t5 ~ t6 ~ t7 ~ t8 ~ t9 ~ t10 ~ t11) => (t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11) }
}

object CypherRow {
  def unapplySeq(row: CypherRow): Option[List[Any]] = Some(row.asList)
}

case class MetaDataItem(column: String, nullable: Boolean, clazz: String)

case class MetaData(ms: List[MetaDataItem]) {
  def get(columnName: String) = dictionary.get(columnName.toUpperCase)

  private lazy val dictionary: Map[String, (String, Boolean, String)] =
    ms.map(m => (m.column.toUpperCase, (m.column, m.nullable, m.clazz))).toMap

  lazy val columnCount = ms.size

  lazy val availableColumns: List[String] = ms.map(_.column)
}

trait CypherRow {

  protected[anormcypher] val data: List[Any]
  protected[anormcypher] val metaData: MetaData

  lazy val asList = data.zip(metaData.ms.map(_.nullable)).map(i => if (i._2) Option(i._1) else i._1)

  lazy val asMap: scala.collection.Map[String, Any] = metaData.ms.map(_.column).zip(asList).toMap

  def get[A](a: String)(implicit c: Column[A]): MayErr[CypherRequestError, A] = CypherParser.get(a)(c)(this) match {
    case Success(a) => Right(a)
    case Error(e) => Left(e)
  }

  private def getType(t: String) = t match {
    case "long" => Class.forName("java.lang.Long")
    case "int" => Class.forName("java.lang.Integer")
    case "boolean" => Class.forName("java.lang.Boolean")
    case _ => Class.forName(t)
  }

  private lazy val ColumnsDictionary: Map[String, Any] = metaData.ms.map(_.column.toUpperCase).zip(data).toMap

  private[anormcypher] def get1(a: String): MayErr[CypherRequestError, Any] = {
    for (
      meta <- metaData.get(a).toRight(ColumnNotFound(a, metaData.availableColumns));
      (column, nullable, clazz) = meta;
      result <- ColumnsDictionary.get(column.toUpperCase).toRight(ColumnNotFound(column, metaData.availableColumns))
    ) yield result
  }

  def apply[B](a: String)(implicit c: Column[B]): B = get[B](a)(c).get

}

case class MockRow(metaData: MetaData, data: List[Any]) extends CypherRow

case class CypherResultRow(metaData: MetaData, data: List[Any]) extends CypherRow {
  override def toString() = s"CypherResultRow(${metaData.ms.zip(data).map(t => "'" + t._1.column + "':" + t._2 + " as " + t._1.clazz).mkString(", ")})"
}

case class CypherStatement(statement: String, parameters: Map[String, Any] = Map()) {

  def apply()(implicit connection: Neo4jConnection, ec: ExecutionContext): Seq[CypherResultRow] =
    Await.result(async(), 30.seconds)

  def async()(implicit connection: Neo4jConnection, ec: ExecutionContext): Future[Seq[CypherResultRow]] =
    connection.sendQuery(this)

  def on(args: (String, Any)*) = this.copy(parameters = parameters ++ args)

  def execute()(implicit connection: Neo4jConnection, ec: ExecutionContext): Boolean = {
    var retVal = true
    try {
      // throws an exception on a query that doesn't succeed.
      apply()
    } catch {
      case e: Exception => retVal = false
    }
    retVal
  }

  def as[T](parser: CypherResultSetParser[T])(implicit connection: Neo4jREST, ec: ExecutionContext): T = {
    Cypher.as[T](parser, apply())
  }

  def list[A](rowParser: CypherRowParser[A])()(implicit connection: Neo4jREST, ec: ExecutionContext): Seq[A] = as(rowParser.*)

  def single[A](rowParser: CypherRowParser[A])()(implicit connection: Neo4jREST, ec: ExecutionContext): A = as(CypherResultSetParser.single(rowParser))

  def singleOpt[A](rowParser: CypherRowParser[A])()(implicit connection: Neo4jREST, ec: ExecutionContext): Option[A] = as(CypherResultSetParser.singleOpt(rowParser))

  def parse[T](parser: CypherResultSetParser[T])()(implicit connection: Neo4jREST, ec: ExecutionContext): T = Cypher.parse[T](parser, apply())

  def executeAsync()(implicit connection: Neo4jREST, ec: ExecutionContext): Future[Boolean] = {
    val p = Promise[Boolean]()
    async().onComplete { x => p.success(x.isSuccess) }
    p.future
  }

  def asAsync[T](parser: CypherResultSetParser[T])(implicit connection: Neo4jREST, ec: ExecutionContext): Future[T] = {
    Cypher.as[T](parser, async())
  }

  def listAsync[A](rowParser: CypherRowParser[A])()(implicit connection: Neo4jREST, ec: ExecutionContext): Future[Seq[A]] = asAsync(rowParser.*)

  def singleAsync[A](rowParser: CypherRowParser[A])()(implicit connection: Neo4jREST, ec: ExecutionContext): Future[A] = asAsync(CypherResultSetParser.single(rowParser))

  def singleOptAsync[A](rowParser: CypherRowParser[A])()(implicit connection: Neo4jREST, ec: ExecutionContext): Future[Option[A]] = asAsync(CypherResultSetParser.singleOpt(rowParser))

  def parseAsync[T](parser: CypherResultSetParser[T])()(implicit connection: Neo4jREST, ec: ExecutionContext): Future[T] = Cypher.parse[T](parser, async())
}


object Cypher {


  def apply(cypher: String) = CypherStatement(cypher)

  def as[T](parser: CypherResultSetParser[T], rs: Future[Seq[CypherResultRow]])(implicit ec: ExecutionContext): Future[T] =
    rs.map { as(parser,_) }
  def as[T](parser: CypherResultSetParser[T], rs: Seq[CypherResultRow]): T =
    parser(rs) match {
      case Success(a) => a
      case Error(e) => sys.error(e.toString)
    }

  def parse[T](parser: CypherResultSetParser[T], rs: Future[Seq[CypherResultRow]])(implicit ec: ExecutionContext): Future[T] =
    rs.map { parse[T](parser,_) }
  def parse[T](parser: CypherResultSetParser[T], rs: Seq[CypherResultRow]): T =
    parser(rs) match {
      case Success(a) => a
      case Error(e) => sys.error(e.toString)
    }

}
