package zio.schema

import java.math.{ BigDecimal, BigInteger }
import java.time._
import java.time.format.DateTimeFormatter
import java.util.UUID

import scala.collection.immutable.ListMap

import zio.Chunk
import zio.schema.meta.{ MetaSchema, Migration }

sealed trait DynamicValue {
  self =>

  def transform(transforms: Chunk[Migration]): Either[String, DynamicValue] =
    transforms.foldRight[Either[String, DynamicValue]](Right(self)) {
      case (transform, Right(value)) => transform.migrate(value)
      case (_, error @ Left(_))      => error
    }

  def toTypedValue[A](implicit schema: Schema[A]): Either[String, A] =
    toTypedValueLazyError.left.map(_.apply())

  def toTypedValueOption[A](implicit schema: Schema[A]): Option[A] =
    toTypedValueLazyError.toOption

  private def toTypedValueLazyError[A](implicit schema: Schema[A]): Either[() => String, A] =
    (self, schema) match {
      case (DynamicValue.Primitive(value, p), Schema.Primitive(p2, _)) if p == p2 =>
        Right(value.asInstanceOf[A])

      case (DynamicValue.Record(_, values), Schema.GenericRecord(_, structure, _)) =>
        DynamicValue.decodeStructure(values, structure.toChunk).asInstanceOf[Either[() => String, A]]

      case (DynamicValue.Record(_, values), s: Schema.Record[A]) =>
        DynamicValue
          .decodeStructure(values, s.structure)
          .map(m => Chunk.fromIterable(m.values))
          .flatMap(values => s.rawConstruct(values).left.map(err => () => err))

      case (DynamicValue.Enumeration(_, (key, value)), s: Schema.Enum[A]) =>
        s.structure.get(key) match {
          case Some(schema) => value.toTypedValueLazyError(schema).asInstanceOf[Either[() => String, A]]
          case None         => Left(() => s"Failed to find case $key in enumN $s")
        }

      case (DynamicValue.LeftValue(value), Schema.Either(schema1, _, _)) =>
        value.toTypedValueLazyError(schema1).map(Left(_))

      case (DynamicValue.RightValue(value), Schema.Either(_, schema1, _)) =>
        value.toTypedValueLazyError(schema1).map(Right(_))

      case (DynamicValue.Tuple(leftValue, rightValue), Schema.Tuple2(leftSchema, rightSchema, _)) =>
        val typedLeft  = leftValue.toTypedValueLazyError(leftSchema)
        val typedRight = rightValue.toTypedValueLazyError(rightSchema)
        (typedLeft, typedRight) match {
          case (Left(e1), Left(e2)) =>
            Left(() => s"Converting generic tuple to typed value failed with errors ${e1()} and ${e2()}")
          case (_, Left(e))         => Left(e)
          case (Left(e), _)         => Left(e)
          case (Right(a), Right(b)) => Right(a -> b)
        }

      case (DynamicValue.Sequence(values), schema: Schema.Sequence[col, t, _]) =>
        values
          .foldLeft[Either[() => String, Chunk[t]]](Right[() => String, Chunk[t]](Chunk.empty)) {
            case (err @ Left(_), _) => err
            case (Right(values), value) =>
              value.toTypedValueLazyError(schema.schemaA).map(values :+ _)
          }
          .map(schema.fromChunk)

      case (DynamicValue.SetValue(values), schema: Schema.Set[t]) =>
        values.foldLeft[Either[() => String, Set[t]]](Right[() => String, Set[t]](Set.empty)) {
          case (err @ Left(_), _) => err
          case (Right(values), value) =>
            value.toTypedValueLazyError(schema.elementSchema).map(values + _)
        }

      case (DynamicValue.SomeValue(value), Schema.Optional(schema: Schema[_], _)) =>
        value.toTypedValueLazyError(schema).map(Some(_))

      case (DynamicValue.NoneValue, Schema.Optional(_, _)) =>
        Right(None)

      case (value, Schema.Transform(schema, f, _, _, _)) =>
        value.toTypedValueLazyError(schema).flatMap(value => f(value).left.map(err => () => err))

      case (DynamicValue.Dictionary(entries), schema: Schema.Map[k, v]) =>
        entries.foldLeft[Either[() => String, Map[k, v]]](Right[() => String, Map[k, v]](Map.empty)) {
          case (err @ Left(_), _) => err
          case (Right(map), entry) => {
            for {
              key   <- entry._1.toTypedValueLazyError(schema.keySchema)
              value <- entry._2.toTypedValueLazyError(schema.valueSchema)
            } yield map ++ Map(key -> value)
          }
        }

      case (_, l @ Schema.Lazy(_)) =>
        toTypedValueLazyError(l.schema)

      case (DynamicValue.Error(message), _) =>
        Left(() => message)

      case (DynamicValue.Tuple(dyn, DynamicValue.DynamicAst(ast)), _) =>
        val valueSchema = ast.toSchema.asInstanceOf[Schema[Any]]
        dyn.toTypedValueLazyError(valueSchema).map(a => (a -> valueSchema).asInstanceOf[A])

      case (dyn, Schema.Dynamic(_)) => Right(dyn)

      case _ =>
        Left(() => s"Failed to cast $self to schema $schema")
    }

}

object DynamicValue {

  //scalafmt: { maxColumn = 400 }
  def fromSchemaAndValue[A](schema: Schema[A], value: A): DynamicValue =
    schema match {

      case l @ Schema.Lazy(_) => fromSchemaAndValue(l.schema, value)

      case Schema.Primitive(p, _) => DynamicValue.Primitive(value, p)

      case Schema.GenericRecord(id, structure, _) =>
        val map: ListMap[String, _] = value
        DynamicValue.Record(
          id,
          ListMap.empty ++ structure.toChunk.map {
            case Schema.Field(key, schema: Schema[a], _, _) =>
              key -> fromSchemaAndValue(schema, map(key).asInstanceOf[a])
          }
        )

      case Schema.Enum1(id, case1, _) =>
        DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, case1.unsafeDeconstruct(value)))

      case Schema.Enum2(id, case1, case2, _) =>
        (case1.deconstruct(value), case2.deconstruct(value)) match {
          case (Some(v1), _) => DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2)) => DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum3(id, case1, case2, case3, _) =>
        (case1.deconstruct(value), case2.deconstruct(value), case3.deconstruct(value)) match {
          case (Some(v1), _, _) => DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _) => DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3)) => DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum4(id, case1, case2, case3, case4, _) =>
        (case1.deconstruct(value), case2.deconstruct(value), case3.deconstruct(value), case4.deconstruct(value)) match {
          case (Some(v1), _, _, _) => DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _) => DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _) => DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4)) => DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum5(id, case1, case2, case3, case4, case5, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _) => DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _) => DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _) => DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _) => DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5)) => DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum6(id, case1, case2, case3, case4, case5, case6, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _) => DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _) => DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _) => DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _) => DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _) => DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6)) => DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum7(id, case1, case2, case3, case4, case5, case6, case7, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _) => DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _) => DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _) => DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _) => DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _) => DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _) => DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7)) => DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum8(id, case1, case2, case3, case4, case5, case6, case7, case8, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8)) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum9(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9)) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum10(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10)) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum11(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11)) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum12(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12)) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum13(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, _) =>
        (case1.deconstruct(value), case2.deconstruct(value), case3.deconstruct(value), case4.deconstruct(value), case5.deconstruct(value), case6.deconstruct(value), case7.deconstruct(value), case8.deconstruct(value), case9.deconstruct(value), case10.deconstruct(value), case11.deconstruct(value), case12.deconstruct(value), case13.deconstruct(value)) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13)) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum14(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, _) =>
        (case1.deconstruct(value), case2.deconstruct(value), case3.deconstruct(value), case4.deconstruct(value), case5.deconstruct(value), case6.deconstruct(value), case7.deconstruct(value), case8.deconstruct(value), case9.deconstruct(value), case10.deconstruct(value), case11.deconstruct(value), case12.deconstruct(value), case13.deconstruct(value), case14.deconstruct(value)) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14)) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum15(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15)) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum16(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16)) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum17(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, case17, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value),
          case17.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _, _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16), _) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v17)) =>
            DynamicValue.Enumeration(id, case17.id -> fromSchemaAndValue(case17.schema, v17))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum18(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, case17, case18, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value),
          case17.deconstruct(value),
          case18.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _, _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _, _, _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16), _, _) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v17), _) =>
            DynamicValue.Enumeration(id, case17.id -> fromSchemaAndValue(case17.schema, v17))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v18)) =>
            DynamicValue.Enumeration(id, case18.id -> fromSchemaAndValue(case18.schema, v18))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum19(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, case17, case18, case19, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value),
          case17.deconstruct(value),
          case18.deconstruct(value),
          case19.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _, _, _, _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16), _, _, _) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v17), _, _) =>
            DynamicValue.Enumeration(id, case17.id -> fromSchemaAndValue(case17.schema, v17))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v18), _) =>
            DynamicValue.Enumeration(id, case18.id -> fromSchemaAndValue(case18.schema, v18))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v19)) =>
            DynamicValue.Enumeration(id, case19.id -> fromSchemaAndValue(case19.schema, v19))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum20(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, case17, case18, case19, case20, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value),
          case17.deconstruct(value),
          case18.deconstruct(value),
          case19.deconstruct(value),
          case20.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16), _, _, _, _) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v17), _, _, _) =>
            DynamicValue.Enumeration(id, case17.id -> fromSchemaAndValue(case17.schema, v17))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v18), _, _) =>
            DynamicValue.Enumeration(id, case18.id -> fromSchemaAndValue(case18.schema, v18))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v19), _) =>
            DynamicValue.Enumeration(id, case19.id -> fromSchemaAndValue(case19.schema, v19))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v20)) =>
            DynamicValue.Enumeration(id, case20.id -> fromSchemaAndValue(case20.schema, v20))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum21(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, case17, case18, case19, case20, case21, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value),
          case17.deconstruct(value),
          case18.deconstruct(value),
          case19.deconstruct(value),
          case20.deconstruct(value),
          case21.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v17), _, _, _, _) =>
            DynamicValue.Enumeration(id, case17.id -> fromSchemaAndValue(case17.schema, v17))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v18), _, _, _) =>
            DynamicValue.Enumeration(id, case18.id -> fromSchemaAndValue(case18.schema, v18))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v19), _, _) =>
            DynamicValue.Enumeration(id, case19.id -> fromSchemaAndValue(case19.schema, v19))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v20), _) =>
            DynamicValue.Enumeration(id, case20.id -> fromSchemaAndValue(case20.schema, v20))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v21)) =>
            DynamicValue.Enumeration(id, case21.id -> fromSchemaAndValue(case21.schema, v21))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }

      case Schema.Enum22(id, case1, case2, case3, case4, case5, case6, case7, case8, case9, case10, case11, case12, case13, case14, case15, case16, case17, case18, case19, case20, case21, case22, _) =>
        (
          case1.deconstruct(value),
          case2.deconstruct(value),
          case3.deconstruct(value),
          case4.deconstruct(value),
          case5.deconstruct(value),
          case6.deconstruct(value),
          case7.deconstruct(value),
          case8.deconstruct(value),
          case9.deconstruct(value),
          case10.deconstruct(value),
          case11.deconstruct(value),
          case12.deconstruct(value),
          case13.deconstruct(value),
          case14.deconstruct(value),
          case15.deconstruct(value),
          case16.deconstruct(value),
          case17.deconstruct(value),
          case18.deconstruct(value),
          case19.deconstruct(value),
          case20.deconstruct(value),
          case21.deconstruct(value),
          case22.deconstruct(value)
        ) match {
          case (Some(v1), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case1.id -> fromSchemaAndValue(case1.schema, v1))
          case (_, Some(v2), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case2.id -> fromSchemaAndValue(case2.schema, v2))
          case (_, _, Some(v3), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case3.id -> fromSchemaAndValue(case3.schema, v3))
          case (_, _, _, Some(v4), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case4.id -> fromSchemaAndValue(case4.schema, v4))
          case (_, _, _, _, Some(v5), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case5.id -> fromSchemaAndValue(case5.schema, v5))
          case (_, _, _, _, _, Some(v6), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case6.id -> fromSchemaAndValue(case6.schema, v6))
          case (_, _, _, _, _, _, Some(v7), _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case7.id -> fromSchemaAndValue(case7.schema, v7))
          case (_, _, _, _, _, _, _, Some(v8), _, _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case8.id -> fromSchemaAndValue(case8.schema, v8))
          case (_, _, _, _, _, _, _, _, Some(v9), _, _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case9.id -> fromSchemaAndValue(case9.schema, v9))
          case (_, _, _, _, _, _, _, _, _, Some(v10), _, _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case10.id -> fromSchemaAndValue(case10.schema, v10))
          case (_, _, _, _, _, _, _, _, _, _, Some(v11), _, _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case11.id -> fromSchemaAndValue(case11.schema, v11))
          case (_, _, _, _, _, _, _, _, _, _, _, Some(v12), _, _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case12.id -> fromSchemaAndValue(case12.schema, v12))
          case (_, _, _, _, _, _, _, _, _, _, _, _, Some(v13), _, _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case13.id -> fromSchemaAndValue(case13.schema, v13))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, Some(v14), _, _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case14.id -> fromSchemaAndValue(case14.schema, v14))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v15), _, _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case15.id -> fromSchemaAndValue(case15.schema, v15))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v16), _, _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case16.id -> fromSchemaAndValue(case16.schema, v16))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v17), _, _, _, _, _) =>
            DynamicValue.Enumeration(id, case17.id -> fromSchemaAndValue(case17.schema, v17))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v18), _, _, _, _) =>
            DynamicValue.Enumeration(id, case18.id -> fromSchemaAndValue(case18.schema, v18))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v19), _, _, _) =>
            DynamicValue.Enumeration(id, case19.id -> fromSchemaAndValue(case19.schema, v19))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v20), _, _) =>
            DynamicValue.Enumeration(id, case20.id -> fromSchemaAndValue(case20.schema, v20))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v21), _) =>
            DynamicValue.Enumeration(id, case21.id -> fromSchemaAndValue(case21.schema, v21))
          case (_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, Some(v22)) =>
            DynamicValue.Enumeration(id, case22.id -> fromSchemaAndValue(case22.schema, v22))
          //This should never happen unless someone manually builds an Enum and doesn't include all cases
          case _ => DynamicValue.NoneValue
        }
      //scalafmt: { maxColumn = 120 }

      case Schema.EnumN(id, cases, _) =>
        cases.toSeq
          .find(_.deconstruct(value).isDefined) match {
          case Some(c) =>
            DynamicValue.Enumeration(
              id,
              c.id -> fromSchemaAndValue(c.schema.asInstanceOf[Schema[Any]], c.unsafeDeconstruct(value))
            )
          case None => DynamicValue.NoneValue
        }

      case Schema.Fail(message, _) => DynamicValue.Error(message)

      case Schema.Sequence(schema, _, toChunk, _, _) =>
        DynamicValue.Sequence(toChunk(value).map(fromSchemaAndValue(schema, _)))

      case Schema.Map(ks: Schema[k], vs: Schema[v], _) =>
        val entries = value.asInstanceOf[Map[k, v]].map {
          case (key, value) => (fromSchemaAndValue(ks, key), fromSchemaAndValue(vs, value))
        }
        DynamicValue.Dictionary(Chunk.fromIterable(entries))

      case Schema.Set(as: Schema[a], _) =>
        DynamicValue.SetValue(value.asInstanceOf[Set[a]].map(fromSchemaAndValue(as, _)))

      case schema: Schema.Either[l, r] =>
        value.asInstanceOf[Either[l, r]] match {
          case Left(value: l)  => DynamicValue.LeftValue(fromSchemaAndValue(schema.left, value))
          case Right(value: r) => DynamicValue.RightValue(fromSchemaAndValue(schema.right, value))
        }

      case schema: Schema.Tuple2[a, b] =>
        val (a: a, b: b) = value.asInstanceOf[(a, b)]
        DynamicValue.Tuple(fromSchemaAndValue(schema.left, a), fromSchemaAndValue(schema.right, b))

      case schema: Schema.Optional[a] =>
        value.asInstanceOf[Option[a]] match {
          case Some(value: a) => DynamicValue.SomeValue(fromSchemaAndValue(schema.schema, value))
          case None           => DynamicValue.NoneValue
        }

      case Schema.Transform(schema, _, g, _, _) =>
        g(value) match {
          case Left(message) => DynamicValue.Error(message)
          case Right(a)      => fromSchemaAndValue(schema, a)
        }

      case Schema.CaseClass0(id, _, _) =>
        DynamicValue.Record(id, ListMap())

      case Schema.CaseClass1(id, f, _, ext, _) =>
        DynamicValue.Record(id, ListMap(f.label -> fromSchemaAndValue(f.schema, ext(value))))

      case Schema.CaseClass2(id, f1, f2, _, ext1, ext2, _) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value))
          )
        )
      case Schema.CaseClass3(id, f1, f2, f3, _, ext1, ext2, ext3, _) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value))
          )
        )
      case Schema.CaseClass4(id, f1, f2, f3, f4, _, ext1, ext2, ext3, ext4, _) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label -> fromSchemaAndValue(f4.schema, ext4(value))
          )
        )
      case Schema.CaseClass5(id, f1, f2, f3, f4, f5, _, ext1, ext2, ext3, ext4, ext5, _) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label -> fromSchemaAndValue(f5.schema, ext5(value))
          )
        )
      case Schema.CaseClass6(id, f1, f2, f3, f4, f5, f6, _, ext1, ext2, ext3, ext4, ext5, ext6, _) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label -> fromSchemaAndValue(f6.schema, ext6(value))
          )
        )
      case Schema.CaseClass7(id, f1, f2, f3, f4, f5, f6, f7, _, ext1, ext2, ext3, ext4, ext5, ext6, ext7, _) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label -> fromSchemaAndValue(f7.schema, ext7(value))
          )
        )
      case Schema.CaseClass8(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label -> fromSchemaAndValue(f8.schema, ext8(value))
          )
        )
      case Schema.CaseClass9(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label -> fromSchemaAndValue(f9.schema, ext9(value))
          )
        )
      case Schema.CaseClass10(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value))
          )
        )
      case Schema.CaseClass11(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value))
          )
        )
      case Schema.CaseClass12(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value))
          )
        )
      case Schema.CaseClass13(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value))
          )
        )
      case Schema.CaseClass14(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value))
          )
        )
      case Schema.CaseClass15(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value))
          )
        )
      case Schema.CaseClass16(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value))
          )
        )
      case Schema.CaseClass17(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          f17,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          ext17,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value)),
            f17.label -> fromSchemaAndValue(f17.schema, ext17(value))
          )
        )
      case Schema.CaseClass18(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          f17,
          f18,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          ext17,
          ext18,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value)),
            f17.label -> fromSchemaAndValue(f17.schema, ext17(value)),
            f18.label -> fromSchemaAndValue(f18.schema, ext18(value))
          )
        )
      case Schema.CaseClass19(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          f17,
          f18,
          f19,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          ext17,
          ext18,
          ext19,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value)),
            f17.label -> fromSchemaAndValue(f17.schema, ext17(value)),
            f18.label -> fromSchemaAndValue(f18.schema, ext18(value)),
            f19.label -> fromSchemaAndValue(f19.schema, ext19(value))
          )
        )
      case Schema.CaseClass20(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          f17,
          f18,
          f19,
          f20,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          ext17,
          ext18,
          ext19,
          ext20,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value)),
            f17.label -> fromSchemaAndValue(f17.schema, ext17(value)),
            f18.label -> fromSchemaAndValue(f18.schema, ext18(value)),
            f19.label -> fromSchemaAndValue(f19.schema, ext19(value)),
            f20.label -> fromSchemaAndValue(f20.schema, ext20(value))
          )
        )
      case Schema.CaseClass21(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          f17,
          f18,
          f19,
          f20,
          f21,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          ext17,
          ext18,
          ext19,
          ext20,
          ext21,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value)),
            f17.label -> fromSchemaAndValue(f17.schema, ext17(value)),
            f18.label -> fromSchemaAndValue(f18.schema, ext18(value)),
            f19.label -> fromSchemaAndValue(f19.schema, ext19(value)),
            f20.label -> fromSchemaAndValue(f20.schema, ext20(value)),
            f21.label -> fromSchemaAndValue(f21.schema, ext21(value))
          )
        )
      case Schema.CaseClass22(
          id,
          f1,
          f2,
          f3,
          f4,
          f5,
          f6,
          f7,
          f8,
          f9,
          f10,
          f11,
          f12,
          f13,
          f14,
          f15,
          f16,
          f17,
          f18,
          f19,
          f20,
          f21,
          f22,
          _,
          ext1,
          ext2,
          ext3,
          ext4,
          ext5,
          ext6,
          ext7,
          ext8,
          ext9,
          ext10,
          ext11,
          ext12,
          ext13,
          ext14,
          ext15,
          ext16,
          ext17,
          ext18,
          ext19,
          ext20,
          ext21,
          ext22,
          _
          ) =>
        DynamicValue.Record(
          id,
          ListMap(
            f1.label  -> fromSchemaAndValue(f1.schema, ext1(value)),
            f2.label  -> fromSchemaAndValue(f2.schema, ext2(value)),
            f3.label  -> fromSchemaAndValue(f3.schema, ext3(value)),
            f4.label  -> fromSchemaAndValue(f4.schema, ext4(value)),
            f5.label  -> fromSchemaAndValue(f5.schema, ext5(value)),
            f6.label  -> fromSchemaAndValue(f6.schema, ext6(value)),
            f7.label  -> fromSchemaAndValue(f7.schema, ext7(value)),
            f8.label  -> fromSchemaAndValue(f8.schema, ext8(value)),
            f9.label  -> fromSchemaAndValue(f9.schema, ext9(value)),
            f10.label -> fromSchemaAndValue(f10.schema, ext10(value)),
            f11.label -> fromSchemaAndValue(f11.schema, ext11(value)),
            f12.label -> fromSchemaAndValue(f12.schema, ext12(value)),
            f13.label -> fromSchemaAndValue(f13.schema, ext13(value)),
            f14.label -> fromSchemaAndValue(f14.schema, ext14(value)),
            f15.label -> fromSchemaAndValue(f15.schema, ext15(value)),
            f16.label -> fromSchemaAndValue(f16.schema, ext16(value)),
            f17.label -> fromSchemaAndValue(f17.schema, ext17(value)),
            f18.label -> fromSchemaAndValue(f18.schema, ext18(value)),
            f19.label -> fromSchemaAndValue(f19.schema, ext19(value)),
            f20.label -> fromSchemaAndValue(f20.schema, ext20(value)),
            f21.label -> fromSchemaAndValue(f21.schema, ext21(value)),
            f22.label -> fromSchemaAndValue(f22.schema, ext22(value))
          )
        )
      case Schema.Dynamic(_) => value
    }

  def decodeStructure(
    values: ListMap[String, DynamicValue],
    structure: Chunk[Schema.Field[_]]
  ): Either[() => String, ListMap[String, _]] = {
    val keys = values.keySet
    keys.foldLeft[Either[() => String, ListMap[String, Any]]](Right(ListMap.empty)) {
      case (Right(record), key) =>
        (structure.find(_.label == key), values.get(key)) match {
          case (Some(field), Some(value)) =>
            value.toTypedValueLazyError(field.schema).map(value => (record + (key -> value)))
          case _ =>
            Left(() => s"$values and $structure have incompatible shape")
        }
      case (Left(string), _) => Left(string)
    }
  }

  final case class Record(id: TypeId, values: ListMap[String, DynamicValue]) extends DynamicValue

  final case class Enumeration(id: TypeId, value: (String, DynamicValue)) extends DynamicValue

  final case class Sequence(values: Chunk[DynamicValue]) extends DynamicValue

  final case class Dictionary(entries: Chunk[(DynamicValue, DynamicValue)]) extends DynamicValue

  final case class SetValue(values: Set[DynamicValue]) extends DynamicValue

  sealed case class Primitive[A](value: A, standardType: StandardType[A]) extends DynamicValue

  sealed case class Singleton[A](instance: A) extends DynamicValue

  final case class SomeValue(value: DynamicValue) extends DynamicValue

  case object NoneValue extends DynamicValue

  sealed case class Tuple(left: DynamicValue, right: DynamicValue) extends DynamicValue

  final case class LeftValue(value: DynamicValue) extends DynamicValue

  final case class RightValue(value: DynamicValue) extends DynamicValue

  final case class DynamicAst(ast: MetaSchema) extends DynamicValue

  final case class Error(message: String) extends DynamicValue

}

private[schema] object DynamicValueSchema {
  self =>

  def apply(): Schema[DynamicValue] = schema

  lazy val schema: Schema[DynamicValue] =
    Schema.EnumN(
      TypeId.fromTypeName("zio.schema.DynamicValue"),
      CaseSet
        .Cons(errorCase, CaseSet.Empty[DynamicValue]())
        .:+:(noneValueCase)
        .:+:(rightValueCase)
        .:+:(leftValueCase)
        .:+:(tupleCase)
        .:+:(someValueCase)
        .:+:(dictionaryCase)
        .:+:(sequenceCase)
        .:+:(setCase)
        .:+:(enumerationCase)
        .:+:(recordCase)
        .:+:(dynamicAstCase)
        .:+:(primitiveUnitCase)
        .:+:(primitiveStringCase)
        .:+:(primitiveBooleanCase)
        .:+:(primitiveShortCase)
        .:+:(primitiveIntCase)
        .:+:(primitiveLongCase)
        .:+:(primitiveFloatCase)
        .:+:(primitiveDoubleCase)
        .:+:(primitiveBinaryCase)
        .:+:(primitiveCharCase)
        .:+:(primitiveBigDecimalCase)
        .:+:(primitiveBigIntegerCase)
        .:+:(primitiveDayOfWeekCase)
        .:+:(primitiveMonthCase)
        .:+:(primitiveMonthDayCase)
        .:+:(primitivePeriodCase)
        .:+:(primitiveYearCase)
        .:+:(primitiveYearMonthCase)
        .:+:(primitiveZoneIdCase)
        .:+:(primitiveZoneOffsetCase)
        .:+:(primitiveInstantCase)
        .:+:(primitiveDurationCase)
        .:+:(primitiveLocalDateCase)
        .:+:(primitiveLocalTimeCase)
        .:+:(primitiveLocalDateTimeCase)
        .:+:(primitiveOffsetTimeCase)
        .:+:(primitiveOffsetDateTimeCase)
        .:+:(primitiveZonedDateTimeCase)
        .:+:(primitiveUUIDCase)
        .:+:(singletonCase)
    )

  implicit val instantStandardType: StandardType[Instant] =
    StandardType.InstantType(DateTimeFormatter.ISO_INSTANT)

  implicit val localDateStandardType: StandardType[LocalDate] =
    StandardType.LocalDateType(DateTimeFormatter.ISO_LOCAL_DATE)

  implicit val localTimeStandardType: StandardType[LocalTime] =
    StandardType.LocalTimeType(DateTimeFormatter.ISO_LOCAL_TIME)

  implicit val localDateTimeStandardType: StandardType[LocalDateTime] =
    StandardType.LocalDateTimeType(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

  implicit val offsetTimeStandardType: StandardType[OffsetTime] =
    StandardType.OffsetTimeType(DateTimeFormatter.ISO_OFFSET_TIME)

  implicit val offsetDateTimeStandardType: StandardType[OffsetDateTime] =
    StandardType.OffsetDateTimeType(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

  implicit val zonedDateTimeStandardType: StandardType[ZonedDateTime] =
    StandardType.ZonedDateTimeType(DateTimeFormatter.ISO_ZONED_DATE_TIME)

  private val errorCase: Schema.Case[DynamicValue.Error, DynamicValue] =
    Schema.Case(
      "Error",
      Schema.CaseClass1[String, DynamicValue.Error](
        TypeId.parse("zio.schema.DynamicValue.Error"),
        Schema.Field("message", Schema.primitive[String]),
        message => DynamicValue.Error(message),
        error => error.message
      ),
      _.asInstanceOf[DynamicValue.Error]
    )

  private val noneValueCase: Schema.Case[DynamicValue.NoneValue.type, DynamicValue] =
    Schema.Case(
      "NoneValue",
      Schema.singleton(None).transform(_ => DynamicValue.NoneValue, _ => None),
      _.asInstanceOf[DynamicValue.NoneValue.type],
      Chunk("case")
    )

  private val rightValueCase: Schema.Case[DynamicValue.RightValue, DynamicValue] =
    Schema.Case(
      "RightValue",
      Schema.CaseClass1[DynamicValue, DynamicValue.RightValue](
        TypeId.parse("zio.schema.DynamicValue.RightValue"),
        Schema.Field("value", Schema.defer(DynamicValueSchema())),
        dynamicValue => DynamicValue.RightValue(dynamicValue),
        rightValue => rightValue.value
      ),
      _.asInstanceOf[DynamicValue.RightValue]
    )

  private val leftValueCase: Schema.Case[DynamicValue.LeftValue, DynamicValue] =
    Schema.Case(
      "LeftValue",
      Schema.CaseClass1[DynamicValue, DynamicValue.LeftValue](
        TypeId.parse("zio.schema.DynamicValue.LeftValue"),
        Schema.Field("value", Schema.defer(DynamicValueSchema())),
        dynamicValue => DynamicValue.LeftValue(dynamicValue),
        leftValue => leftValue.value
      ),
      _.asInstanceOf[DynamicValue.LeftValue]
    )

  private val tupleCase: Schema.Case[DynamicValue.Tuple, DynamicValue] =
    Schema.Case(
      "Tuple",
      Schema.CaseClass2[DynamicValue, DynamicValue, DynamicValue.Tuple](
        TypeId.parse("zio.schema.DynamicValue.Tuple"),
        Schema.Field("left", Schema.defer(DynamicValueSchema())),
        Schema.Field("right", Schema.defer(DynamicValueSchema())),
        (left, right) => DynamicValue.Tuple(left, right),
        tuple => tuple.left,
        tuple => tuple.right
      ),
      _.asInstanceOf[DynamicValue.Tuple]
    )

  private val someValueCase: Schema.Case[DynamicValue.SomeValue, DynamicValue] =
    Schema.Case(
      "SomeValue",
      Schema.CaseClass1[DynamicValue, DynamicValue.SomeValue](
        TypeId.parse("zio.schema.DynamicValue.SomeValue"),
        Schema.Field("value", Schema.defer(DynamicValueSchema())),
        dv => DynamicValue.SomeValue(dv),
        someValue => someValue.value
      ),
      _.asInstanceOf[DynamicValue.SomeValue]
    )

  private val dictionaryCase: Schema.Case[DynamicValue.Dictionary, DynamicValue] =
    Schema.Case(
      "Dictionary",
      Schema.CaseClass1[Chunk[(DynamicValue, DynamicValue)], DynamicValue.Dictionary](
        TypeId.parse("zio.schema.DynamicValue.Dictionary"),
        Schema.Field(
          "entries",
          Schema.defer(Schema.chunk(Schema.tuple2(DynamicValueSchema(), DynamicValueSchema())))
        ),
        chunk => DynamicValue.Dictionary(chunk),
        dictionary => dictionary.entries
      ),
      _.asInstanceOf[DynamicValue.Dictionary]
    )

  private val sequenceCase: Schema.Case[DynamicValue.Sequence, DynamicValue] =
    Schema.Case(
      "Sequence",
      Schema.CaseClass1[Chunk[DynamicValue], DynamicValue.Sequence](
        TypeId.parse("zio.schema.DynamicValue.Sequence"),
        Schema.Field("values", Schema.defer(Schema.chunk(DynamicValueSchema()))),
        chunk => DynamicValue.Sequence(chunk),
        seq => seq.values
      ),
      _.asInstanceOf[DynamicValue.Sequence]
    )

  private val setCase: Schema.Case[DynamicValue.SetValue, DynamicValue] =
    Schema.Case(
      "SetValue",
      Schema.CaseClass1[Set[DynamicValue], DynamicValue.SetValue](
        TypeId.parse("zio.schema.DynamicValue.SetValue"),
        Schema.Field("values", Schema.defer(Schema.set(DynamicValueSchema()))),
        set => DynamicValue.SetValue(set),
        seq => seq.values
      ),
      _.asInstanceOf[DynamicValue.SetValue]
    )

  private val enumerationCase: Schema.Case[DynamicValue.Enumeration, DynamicValue] =
    Schema.Case(
      "Enumeration",
      Schema.CaseClass2[TypeId, (String, DynamicValue), DynamicValue.Enumeration](
        TypeId.parse("zio.schema.DynamicValue.Enumeration"),
        Schema.Field("id", Schema[TypeId]),
        Schema.Field("value", Schema.defer(Schema.tuple2(Schema.primitive[String], DynamicValueSchema()))),
        (id, value) => DynamicValue.Enumeration(id, value),
        enumeration => enumeration.id,
        enumeration => enumeration.value
      ),
      _.asInstanceOf[DynamicValue.Enumeration]
    )

  private val recordCase: Schema.Case[DynamicValue.Record, DynamicValue] =
    Schema.Case(
      "Record",
      Schema.CaseClass2[TypeId, Chunk[(String, DynamicValue)], DynamicValue.Record](
        TypeId.parse("zio.schema.DynamicValue.Record"),
        Schema.Field("id", Schema[TypeId]),
        Schema
          .Field("values", Schema.defer(Schema.chunk(Schema.tuple2(Schema.primitive[String], DynamicValueSchema())))),
        (id, chunk) => DynamicValue.Record(id, ListMap(chunk.toSeq: _*)),
        record => record.id,
        record => Chunk.fromIterable(record.values)
      ),
      _.asInstanceOf[DynamicValue.Record]
    )

  private val dynamicAstCase: Schema.Case[DynamicValue.DynamicAst, DynamicValue] =
    Schema.Case(
      "DynamicAst",
      Schema.CaseClass1[MetaSchema, DynamicValue.DynamicAst](
        TypeId.parse("zio.schema.DynamicValue.DynamicAst"),
        Schema.Field("ast", MetaSchema.schema),
        schemaAst => DynamicValue.DynamicAst(schemaAst),
        dynamicAst => dynamicAst.ast
      ),
      _.asInstanceOf[DynamicValue.DynamicAst]
    )

  private val singletonCase: Schema.Case[DynamicValue.Singleton[Any], DynamicValue] =
    Schema.Case(
      "Singleton",
      Schema[Unit].transform(_ => DynamicValue.Singleton(()), _ => ()),
      _.asInstanceOf[DynamicValue.Singleton[Any]]
    )

  private val primitiveUnitCase: Schema.Case[DynamicValue.Primitive[Unit], DynamicValue] =
    Schema.Case(
      "Unit",
      Schema.primitive[Unit].transform(unit => DynamicValue.Primitive(unit, StandardType[Unit]), _.value), {
        case dv @ DynamicValue.Primitive((), _) => dv.asInstanceOf[DynamicValue.Primitive[Unit]]
        case _                                  => throw new IllegalArgumentException
      }
    )

  private val primitiveStringCase: Schema.Case[DynamicValue.Primitive[String], DynamicValue] =
    Schema.Case(
      "String",
      Schema.primitive[String].transform(s => DynamicValue.Primitive(s, StandardType[String]), _.value), {
        case dv @ DynamicValue.Primitive(_: String, _) => dv.asInstanceOf[DynamicValue.Primitive[String]]
        case _                                         => throw new IllegalArgumentException
      }
    )

  private val primitiveBooleanCase: Schema.Case[DynamicValue.Primitive[Boolean], DynamicValue] =
    Schema.Case(
      "Boolean",
      Schema.primitive[Boolean].transform(b => DynamicValue.Primitive(b, StandardType[Boolean]), _.value), {
        case dv @ DynamicValue.Primitive(_: Boolean, _) => dv.asInstanceOf[DynamicValue.Primitive[Boolean]]
        case _                                          => throw new IllegalArgumentException
      }
    )

  private val primitiveShortCase: Schema.Case[DynamicValue.Primitive[Short], DynamicValue] =
    Schema.Case(
      "Short",
      Schema.primitive[Short].transform(sh => DynamicValue.Primitive(sh, StandardType[Short]), _.value), {
        case dv @ DynamicValue.Primitive(_: Short, _) => dv.asInstanceOf[DynamicValue.Primitive[Short]]
        case _                                        => throw new IllegalArgumentException
      }
    )

  private val primitiveIntCase: Schema.Case[DynamicValue.Primitive[Int], DynamicValue] =
    Schema.Case(
      "Int",
      Schema.primitive[Int].transform(i => DynamicValue.Primitive(i, StandardType[Int]), _.value), {
        case dv @ DynamicValue.Primitive(_: Int, _) => dv.asInstanceOf[DynamicValue.Primitive[Int]]
        case _                                      => throw new IllegalArgumentException
      }
    )

  private val primitiveLongCase: Schema.Case[DynamicValue.Primitive[Long], DynamicValue] =
    Schema.Case(
      "Long",
      Schema.primitive[Long].transform(l => DynamicValue.Primitive(l, StandardType[Long]), _.value), {
        case dv @ DynamicValue.Primitive(_: Long, _) => dv.asInstanceOf[DynamicValue.Primitive[Long]]
        case _                                       => throw new IllegalArgumentException
      }
    )

  private val primitiveFloatCase: Schema.Case[DynamicValue.Primitive[Float], DynamicValue] =
    Schema.Case(
      "Float",
      Schema.primitive[Float].transform(f => DynamicValue.Primitive(f, StandardType[Float]), _.value), {
        case dv @ DynamicValue.Primitive(_: Float, _) => dv.asInstanceOf[DynamicValue.Primitive[Float]]
        case _                                        => throw new IllegalArgumentException
      }
    )

  private val primitiveDoubleCase: Schema.Case[DynamicValue.Primitive[Double], DynamicValue] =
    Schema.Case(
      "Double",
      Schema.primitive[Double].transform(d => DynamicValue.Primitive(d, StandardType[Double]), _.value), {
        case dv @ DynamicValue.Primitive(_: Double, _) => dv.asInstanceOf[DynamicValue.Primitive[Double]]
        case _                                         => throw new IllegalArgumentException
      }
    )

  private val primitiveBinaryCase: Schema.Case[DynamicValue.Primitive[Chunk[Byte]], DynamicValue] =
    Schema.Case(
      "Binary",
      Schema.primitive[Chunk[Byte]].transform(ch => DynamicValue.Primitive(ch, StandardType[Chunk[Byte]]), _.value), {
        case dv @ DynamicValue.Primitive(_: Chunk[_], _) => dv.asInstanceOf[DynamicValue.Primitive[Chunk[Byte]]]
        case _                                           => throw new IllegalArgumentException
      }
    )

  private val primitiveCharCase: Schema.Case[DynamicValue.Primitive[Char], DynamicValue] =
    Schema.Case(
      "Char",
      Schema.primitive[Char].transform(ch => DynamicValue.Primitive(ch, StandardType[Char]), _.value), {
        case dv @ DynamicValue.Primitive(_: Char, _) => dv.asInstanceOf[DynamicValue.Primitive[Char]]
        case _                                       => throw new IllegalArgumentException
      }
    )

  private val primitiveBigDecimalCase: Schema.Case[DynamicValue.Primitive[BigDecimal], DynamicValue] =
    Schema.Case(
      "BigDecimal",
      Schema.primitive[BigDecimal].transform(bd => DynamicValue.Primitive(bd, StandardType[BigDecimal]), _.value), {
        case dv @ DynamicValue.Primitive(_: BigDecimal, _) => dv.asInstanceOf[DynamicValue.Primitive[BigDecimal]]
        case _                                             => throw new IllegalArgumentException
      }
    )

  private val primitiveBigIntegerCase: Schema.Case[DynamicValue.Primitive[BigInteger], DynamicValue] =
    Schema.Case(
      "BigInteger",
      Schema.primitive[BigInteger].transform(bi => DynamicValue.Primitive(bi, StandardType[BigInteger]), _.value), {
        case dv @ DynamicValue.Primitive(_: BigInteger, _) => dv.asInstanceOf[DynamicValue.Primitive[BigInteger]]
        case _                                             => throw new IllegalArgumentException
      }
    )

  private val primitiveDayOfWeekCase: Schema.Case[DynamicValue.Primitive[DayOfWeek], DynamicValue] =
    Schema.Case(
      "DayOfWeek",
      Schema.primitive[DayOfWeek].transform(dw => DynamicValue.Primitive(dw, StandardType[DayOfWeek]), _.value), {
        case dv @ DynamicValue.Primitive(_: DayOfWeek, _) => dv.asInstanceOf[DynamicValue.Primitive[DayOfWeek]]
        case _                                            => throw new IllegalArgumentException
      }
    )

  private val primitiveMonthCase: Schema.Case[DynamicValue.Primitive[Month], DynamicValue] =
    Schema.Case(
      "Month",
      Schema.primitive[Month].transform(m => DynamicValue.Primitive(m, StandardType[Month]), _.value), {
        case dv @ DynamicValue.Primitive(_: Month, _) => dv.asInstanceOf[DynamicValue.Primitive[Month]]
        case _                                        => throw new IllegalArgumentException
      }
    )

  private val primitiveMonthDayCase: Schema.Case[DynamicValue.Primitive[MonthDay], DynamicValue] =
    Schema.Case(
      "MonthDay",
      Schema.primitive[MonthDay].transform(md => DynamicValue.Primitive(md, StandardType[MonthDay]), _.value), {
        case dv @ DynamicValue.Primitive(_: MonthDay, _) => dv.asInstanceOf[DynamicValue.Primitive[MonthDay]]
        case _                                           => throw new IllegalArgumentException
      }
    )

  private val primitivePeriodCase: Schema.Case[DynamicValue.Primitive[Period], DynamicValue] =
    Schema.Case(
      "Period",
      Schema.primitive[Period].transform(p => DynamicValue.Primitive(p, StandardType[Period]), _.value), {
        case dv @ DynamicValue.Primitive(_: Period, _) => dv.asInstanceOf[DynamicValue.Primitive[Period]]
        case _                                         => throw new IllegalArgumentException
      }
    )

  private val primitiveYearCase: Schema.Case[DynamicValue.Primitive[Year], DynamicValue] =
    Schema.Case(
      "Year",
      Schema.primitive[Year].transform(y => DynamicValue.Primitive(y, StandardType[Year]), _.value), {
        case dv @ DynamicValue.Primitive(_: Year, _) => dv.asInstanceOf[DynamicValue.Primitive[Year]]
        case _                                       => throw new IllegalArgumentException
      }
    )

  private val primitiveYearMonthCase: Schema.Case[DynamicValue.Primitive[YearMonth], DynamicValue] =
    Schema.Case(
      "YearMonth",
      Schema.primitive[YearMonth].transform(ym => DynamicValue.Primitive(ym, StandardType[YearMonth]), _.value), {
        case dv @ DynamicValue.Primitive(_: YearMonth, _) => dv.asInstanceOf[DynamicValue.Primitive[YearMonth]]
        case _                                            => throw new IllegalArgumentException
      }
    )

  private val primitiveZoneIdCase: Schema.Case[DynamicValue.Primitive[ZoneId], DynamicValue] =
    Schema.Case(
      "ZoneId",
      Schema.primitive[ZoneId].transform(zid => DynamicValue.Primitive(zid, StandardType[ZoneId]), _.value), {
        case dv @ DynamicValue.Primitive(_: ZoneId, _) => dv.asInstanceOf[DynamicValue.Primitive[ZoneId]]
        case _                                         => throw new IllegalArgumentException
      }
    )

  private val primitiveZoneOffsetCase: Schema.Case[DynamicValue.Primitive[ZoneOffset], DynamicValue] =
    Schema.Case(
      "ZoneOffset",
      Schema.primitive[ZoneOffset].transform(zo => DynamicValue.Primitive(zo, StandardType[ZoneOffset]), _.value), {
        case dv @ DynamicValue.Primitive(_: ZoneOffset, _) => dv.asInstanceOf[DynamicValue.Primitive[ZoneOffset]]
        case _                                             => throw new IllegalArgumentException
      }
    )

  private val primitiveInstantCase: Schema.Case[DynamicValue.Primitive[Instant], DynamicValue] =
    Schema.Case(
      "Instant",
      Schema.primitive[Instant].transform(i => DynamicValue.Primitive(i, StandardType[Instant]), _.value), {
        case dv @ DynamicValue.Primitive(_: Instant, _) => dv.asInstanceOf[DynamicValue.Primitive[Instant]]
        case _                                          => throw new IllegalArgumentException
      }
    )

  private val primitiveDurationCase: Schema.Case[DynamicValue.Primitive[Duration], DynamicValue] =
    Schema.Case(
      "Duration",
      Schema.primitive[Duration].transform(i => DynamicValue.Primitive(i, StandardType[Duration]), _.value), {
        case dv @ DynamicValue.Primitive(_: Duration, _) => dv.asInstanceOf[DynamicValue.Primitive[Duration]]
        case _                                           => throw new IllegalArgumentException
      }
    )

  private val primitiveLocalDateCase: Schema.Case[DynamicValue.Primitive[LocalDate], DynamicValue] =
    Schema.Case(
      "LocalDate",
      Schema.primitive[LocalDate].transform(ld => DynamicValue.Primitive(ld, StandardType[LocalDate]), _.value), {
        case dv @ DynamicValue.Primitive(_: LocalDate, _) => dv.asInstanceOf[DynamicValue.Primitive[LocalDate]]
        case _                                            => throw new IllegalArgumentException
      }
    )

  private val primitiveLocalTimeCase: Schema.Case[DynamicValue.Primitive[LocalTime], DynamicValue] =
    Schema.Case(
      "LocalTime",
      Schema.primitive[LocalTime].transform(lt => DynamicValue.Primitive(lt, StandardType[LocalTime]), _.value), {
        case dv @ DynamicValue.Primitive(_: LocalTime, _) => dv.asInstanceOf[DynamicValue.Primitive[LocalTime]]
        case _                                            => throw new IllegalArgumentException
      }
    )

  private val primitiveLocalDateTimeCase: Schema.Case[DynamicValue.Primitive[LocalDateTime], DynamicValue] =
    Schema.Case(
      "LocalDateTime",
      Schema
        .primitive[LocalDateTime]
        .transform(ldt => DynamicValue.Primitive(ldt, StandardType[LocalDateTime]), _.value), {
        case dv @ DynamicValue.Primitive(_: LocalDateTime, _) => dv.asInstanceOf[DynamicValue.Primitive[LocalDateTime]]
        case _                                                => throw new IllegalArgumentException
      }
    )

  private val primitiveOffsetTimeCase: Schema.Case[DynamicValue.Primitive[OffsetTime], DynamicValue] =
    Schema.Case(
      "OffsetTime",
      Schema.primitive[OffsetTime].transform(ot => DynamicValue.Primitive(ot, StandardType[OffsetTime]), _.value), {
        case dv @ DynamicValue.Primitive(_: OffsetTime, _) => dv.asInstanceOf[DynamicValue.Primitive[OffsetTime]]
        case _                                             => throw new IllegalArgumentException
      }
    )

  private val primitiveOffsetDateTimeCase: Schema.Case[DynamicValue.Primitive[OffsetDateTime], DynamicValue] =
    Schema.Case(
      "OffsetDateTime",
      Schema
        .primitive[OffsetDateTime]
        .transform(odt => DynamicValue.Primitive(odt, StandardType[OffsetDateTime]), _.value), {
        case dv @ DynamicValue.Primitive(_: OffsetDateTime, _) =>
          dv.asInstanceOf[DynamicValue.Primitive[OffsetDateTime]]
        case _ => throw new IllegalArgumentException
      }
    )

  private val primitiveZonedDateTimeCase: Schema.Case[DynamicValue.Primitive[ZonedDateTime], DynamicValue] =
    Schema.Case(
      "ZonedDateTime",
      Schema
        .primitive[ZonedDateTime]
        .transform(zdt => DynamicValue.Primitive(zdt, StandardType[ZonedDateTime]), _.value), {
        case dv @ DynamicValue.Primitive(_: ZonedDateTime, _) => dv.asInstanceOf[DynamicValue.Primitive[ZonedDateTime]]
        case _                                                => throw new IllegalArgumentException
      }
    )

  private val primitiveUUIDCase: Schema.Case[DynamicValue.Primitive[UUID], DynamicValue] =
    Schema.Case(
      "UUID",
      Schema.primitive[UUID].transform(uuid => DynamicValue.Primitive(uuid, StandardType[UUID]), _.value), {
        case dv @ DynamicValue.Primitive(_: UUID, _) => dv.asInstanceOf[DynamicValue.Primitive[UUID]]
        case _                                       => throw new IllegalArgumentException
      }
    )

}
