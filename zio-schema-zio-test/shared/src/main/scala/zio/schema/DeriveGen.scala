package zio.schema

import java.time.format.DateTimeFormatter

import scala.collection.immutable.ListMap

import zio.Chunk
import zio.schema.meta.{ MetaSchema, NodePath }
import zio.test.{ Gen, Sized }

object DeriveGen {

  // scalafmt: { maxColumn = 400 }
  def gen[A](implicit schema: Schema[A]): Gen[Sized, A] =
    schema match {
      case Schema.Enum1(_, c1, _)                                                                                                                                              => genEnum(c1)
      case Schema.Enum2(_, c1, c2, _)                                                                                                                                          => genEnum(c1, c2)
      case Schema.Enum3(_, c1, c2, c3, _)                                                                                                                                      => genEnum(c1, c2, c3)
      case Schema.Enum4(_, c1, c2, c3, c4, _)                                                                                                                                  => genEnum(c1, c2, c3, c4)
      case Schema.Enum5(_, c1, c2, c3, c4, c5, _)                                                                                                                              => genEnum(c1, c2, c3, c4, c5)
      case Schema.Enum6(_, c1, c2, c3, c4, c5, c6, _)                                                                                                                          => genEnum(c1, c2, c3, c4, c5, c6)
      case Schema.Enum7(_, c1, c2, c3, c4, c5, c6, c7, _)                                                                                                                      => genEnum(c1, c2, c3, c4, c5, c6, c7)
      case Schema.Enum8(_, c1, c2, c3, c4, c5, c6, c7, c8, _)                                                                                                                  => genEnum(c1, c2, c3, c4, c5, c6, c7, c8)
      case Schema.Enum9(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, _)                                                                                                              => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9)
      case Schema.Enum10(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, _)                                                                                                        => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10)
      case Schema.Enum11(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, _)                                                                                                   => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11)
      case Schema.Enum12(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, _)                                                                                              => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12)
      case Schema.Enum13(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, _)                                                                                         => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13)
      case Schema.Enum14(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, _)                                                                                    => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14)
      case Schema.Enum15(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, _)                                                                               => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15)
      case Schema.Enum16(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, _)                                                                          => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16)
      case Schema.Enum17(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, _)                                                                     => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17)
      case Schema.Enum18(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, _)                                                                => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18)
      case Schema.Enum19(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, _)                                                           => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19)
      case Schema.Enum20(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, _)                                                      => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20)
      case Schema.Enum21(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, _)                                                 => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21)
      case Schema.Enum22(_, c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, c22, _)                                            => genEnum(c1, c2, c3, c4, c5, c6, c7, c8, c9, c10, c11, c12, c13, c14, c15, c16, c17, c18, c19, c20, c21, c22)
      case Schema.EnumN(_, caseSet, _)                                                                                                                                         => genEnum(caseSet.toSeq.asInstanceOf[Seq[Schema.Case[_, A]]]: _*)
      case c @ Schema.CaseClass0(_, _, _)                                                                                                                                      => genCaseClass0(c)
      case c @ Schema.CaseClass1(_, _, _, _, _)                                                                                                                                => genCaseClass1(c)
      case c @ Schema.CaseClass2(_, _, _, _, _, _, _)                                                                                                                          => genCaseClass2(c)
      case c @ Schema.CaseClass3(_, _, _, _, _, _, _, _, _)                                                                                                                    => genCaseClass3(c)
      case c @ Schema.CaseClass4(_, _, _, _, _, _, _, _, _, _, _)                                                                                                              => genCaseClass4(c)
      case c @ Schema.CaseClass5(_, _, _, _, _, _, _, _, _, _, _, _, _)                                                                                                        => genCaseClass5(c)
      case c @ Schema.CaseClass6(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                                                                  => genCaseClass6(c)
      case c @ Schema.CaseClass7(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                                                            => genCaseClass7(c)
      case c @ Schema.CaseClass8(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                                                      => genCaseClass8(c)
      case c @ Schema.CaseClass9(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                                                => genCaseClass9(c)
      case c @ Schema.CaseClass10(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                                         => genCaseClass10(c)
      case c @ Schema.CaseClass11(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                                   => genCaseClass11(c)
      case c @ Schema.CaseClass12(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                             => genCaseClass12(c)
      case c @ Schema.CaseClass13(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                       => genCaseClass13(c)
      case c @ Schema.CaseClass14(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                                 => genCaseClass14(c)
      case c @ Schema.CaseClass15(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                           => genCaseClass15(c)
      case c @ Schema.CaseClass16(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                                     => genCaseClass16(c)
      case c @ Schema.CaseClass17(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                               => genCaseClass17(c)
      case c @ Schema.CaseClass18(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                         => genCaseClass18(c)
      case c @ Schema.CaseClass19(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)                   => genCaseClass19(c)
      case c @ Schema.CaseClass20(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)             => genCaseClass20(c)
      case c @ Schema.CaseClass21(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _)       => genCaseClass21(c)
      case c @ Schema.CaseClass22(_, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _, _) => genCaseClass22(c)
      case generic @ Schema.GenericRecord(_, _, _)                                                                                                                             => genGenericRecord(generic).map(_.asInstanceOf[A])
      case seq @ Schema.Sequence(_, _, _, _, _)                                                                                                                                => genSequence(seq)
      case map @ Schema.Map(_, _, _)                                                                                                                                           => genMap(map)
      case set @ Schema.Set(_, _)                                                                                                                                              => genSet(set)
      case transform @ Schema.Transform(_, _, _, _, _)                                                                                                                         => genTransform(transform)
      case Schema.Primitive(standardType, _)                                                                                                                                   => genPrimitive(standardType)
      case optional @ Schema.Optional(_, _)                                                                                                                                    => genOptional(optional)
      case fail @ Schema.Fail(_, _)                                                                                                                                            => genFail(fail)
      case tuple @ Schema.Tuple2(_, _, _)                                                                                                                                      => genTuple(tuple)
      case either @ Schema.Either(_, _, _)                                                                                                                                     => genEither(either)
      case lazzy @ Schema.Lazy(_)                                                                                                                                              => genLazy(lazzy)
      case Schema.Dynamic(_)                                                                                                                                                   => gen(DynamicValueSchema())
    } // scalafmt: { maxColumn = 120 }

  private def genEnum[Z](cases: Schema.Case[_, Z]*) =
    Gen.elements(cases: _*).flatMap(c => gen(c.schema).map(_.asInstanceOf[Z]))

  private def genCaseClass0[Z](caseClass0: Schema.CaseClass0[Z]): Gen[Sized, Z] =
    Gen.elements(caseClass0.construct())

  private def genCaseClass1[A, Z](caseClass1: Schema.CaseClass1[A, Z]): Gen[Sized, Z] =
    gen(caseClass1.field.schema).map(caseClass1.construct)

  private def genCaseClass2[A1, A2, Z](caseClass2: Schema.CaseClass2[A1, A2, Z]): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass2.field1.schema)
      f2 <- gen(caseClass2.field2.schema)
    } yield caseClass2.construct(f1, f2)

  private def genCaseClass3[A1, A2, A3, Z](caseClass3: Schema.CaseClass3[A1, A2, A3, Z]): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass3.field1.schema)
      f2 <- gen(caseClass3.field2.schema)
      f3 <- gen(caseClass3.field3.schema)
    } yield caseClass3.construct(f1, f2, f3)

  private def genCaseClass4[A1, A2, A3, A4, Z](
    caseClass4: Schema.CaseClass4[A1, A2, A3, A4, Z]
  ): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass4.field1.schema)
      f2 <- gen(caseClass4.field2.schema)
      f3 <- gen(caseClass4.field3.schema)
      f4 <- gen(caseClass4.field4.schema)
    } yield caseClass4.construct(f1, f2, f3, f4)

  private def genCaseClass5[A1, A2, A3, A4, A5, Z](
    caseClass5: Schema.CaseClass5[A1, A2, A3, A4, A5, Z]
  ): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass5.field1.schema)
      f2 <- gen(caseClass5.field2.schema)
      f3 <- gen(caseClass5.field3.schema)
      f4 <- gen(caseClass5.field4.schema)
      f5 <- gen(caseClass5.field5.schema)
    } yield caseClass5.construct(f1, f2, f3, f4, f5)

  private def genCaseClass6[A1, A2, A3, A4, A5, A6, Z](
    caseClass6: Schema.CaseClass6[A1, A2, A3, A4, A5, A6, Z]
  ): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass6.field1.schema)
      f2 <- gen(caseClass6.field2.schema)
      f3 <- gen(caseClass6.field3.schema)
      f4 <- gen(caseClass6.field4.schema)
      f5 <- gen(caseClass6.field5.schema)
      f6 <- gen(caseClass6.field6.schema)
    } yield caseClass6.construct(f1, f2, f3, f4, f5, f6)

  private def genCaseClass7[A1, A2, A3, A4, A5, A6, A7, Z](
    caseClass7: Schema.CaseClass7[A1, A2, A3, A4, A5, A6, A7, Z]
  ): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass7.field1.schema)
      f2 <- gen(caseClass7.field2.schema)
      f3 <- gen(caseClass7.field3.schema)
      f4 <- gen(caseClass7.field4.schema)
      f5 <- gen(caseClass7.field5.schema)
      f6 <- gen(caseClass7.field6.schema)
      f7 <- gen(caseClass7.field7.schema)
    } yield caseClass7.construct(f1, f2, f3, f4, f5, f6, f7)

  private def genCaseClass8[A1, A2, A3, A4, A5, A6, A7, A8, Z](
    caseClass8: Schema.CaseClass8[A1, A2, A3, A4, A5, A6, A7, A8, Z]
  ): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass8.field1.schema)
      f2 <- gen(caseClass8.field2.schema)
      f3 <- gen(caseClass8.field3.schema)
      f4 <- gen(caseClass8.field4.schema)
      f5 <- gen(caseClass8.field5.schema)
      f6 <- gen(caseClass8.field6.schema)
      f7 <- gen(caseClass8.field7.schema)
      f8 <- gen(caseClass8.field8.schema)
    } yield caseClass8.construct(f1, f2, f3, f4, f5, f6, f7, f8)

  private def genCaseClass9[A1, A2, A3, A4, A5, A6, A7, A8, A9, Z](
    caseClass9: Schema.CaseClass9[A1, A2, A3, A4, A5, A6, A7, A8, A9, Z]
  ): Gen[Sized, Z] =
    for {
      f1 <- gen(caseClass9.field1.schema)
      f2 <- gen(caseClass9.field2.schema)
      f3 <- gen(caseClass9.field3.schema)
      f4 <- gen(caseClass9.field4.schema)
      f5 <- gen(caseClass9.field5.schema)
      f6 <- gen(caseClass9.field6.schema)
      f7 <- gen(caseClass9.field7.schema)
      f8 <- gen(caseClass9.field8.schema)
      f9 <- gen(caseClass9.field9.schema)
    } yield caseClass9.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9)

  private def genCaseClass10[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, Z](
    caseClass10: Schema.CaseClass10[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass10.field1.schema)
      f2  <- gen(caseClass10.field2.schema)
      f3  <- gen(caseClass10.field3.schema)
      f4  <- gen(caseClass10.field4.schema)
      f5  <- gen(caseClass10.field5.schema)
      f6  <- gen(caseClass10.field6.schema)
      f7  <- gen(caseClass10.field7.schema)
      f8  <- gen(caseClass10.field8.schema)
      f9  <- gen(caseClass10.field9.schema)
      f10 <- gen(caseClass10.field10.schema)
    } yield caseClass10.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10)

  private def genCaseClass11[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, Z](
    caseClass11: Schema.CaseClass11[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass11.field1.schema)
      f2  <- gen(caseClass11.field2.schema)
      f3  <- gen(caseClass11.field3.schema)
      f4  <- gen(caseClass11.field4.schema)
      f5  <- gen(caseClass11.field5.schema)
      f6  <- gen(caseClass11.field6.schema)
      f7  <- gen(caseClass11.field7.schema)
      f8  <- gen(caseClass11.field8.schema)
      f9  <- gen(caseClass11.field9.schema)
      f10 <- gen(caseClass11.field10.schema)
      f11 <- gen(caseClass11.field11.schema)
    } yield caseClass11.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11)

  private def genCaseClass12[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, Z](
    caseClass12: Schema.CaseClass12[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass12.field1.schema)
      f2  <- gen(caseClass12.field2.schema)
      f3  <- gen(caseClass12.field3.schema)
      f4  <- gen(caseClass12.field4.schema)
      f5  <- gen(caseClass12.field5.schema)
      f6  <- gen(caseClass12.field6.schema)
      f7  <- gen(caseClass12.field7.schema)
      f8  <- gen(caseClass12.field8.schema)
      f9  <- gen(caseClass12.field9.schema)
      f10 <- gen(caseClass12.field10.schema)
      f11 <- gen(caseClass12.field11.schema)
      f12 <- gen(caseClass12.field12.schema)
    } yield caseClass12.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12)

  private def genCaseClass13[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, Z](
    caseClass13: Schema.CaseClass13[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass13.field1.schema)
      f2  <- gen(caseClass13.field2.schema)
      f3  <- gen(caseClass13.field3.schema)
      f4  <- gen(caseClass13.field4.schema)
      f5  <- gen(caseClass13.field5.schema)
      f6  <- gen(caseClass13.field6.schema)
      f7  <- gen(caseClass13.field7.schema)
      f8  <- gen(caseClass13.field8.schema)
      f9  <- gen(caseClass13.field9.schema)
      f10 <- gen(caseClass13.field10.schema)
      f11 <- gen(caseClass13.field11.schema)
      f12 <- gen(caseClass13.field12.schema)
      f13 <- gen(caseClass13.field13.schema)
    } yield caseClass13.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13)

  private def genCaseClass14[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, Z](
    caseClass14: Schema.CaseClass14[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass14.field1.schema)
      f2  <- gen(caseClass14.field2.schema)
      f3  <- gen(caseClass14.field3.schema)
      f4  <- gen(caseClass14.field4.schema)
      f5  <- gen(caseClass14.field5.schema)
      f6  <- gen(caseClass14.field6.schema)
      f7  <- gen(caseClass14.field7.schema)
      f8  <- gen(caseClass14.field8.schema)
      f9  <- gen(caseClass14.field9.schema)
      f10 <- gen(caseClass14.field10.schema)
      f11 <- gen(caseClass14.field11.schema)
      f12 <- gen(caseClass14.field12.schema)
      f13 <- gen(caseClass14.field13.schema)
      f14 <- gen(caseClass14.field14.schema)
    } yield caseClass14.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14)

  private def genCaseClass15[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, Z](
    caseClass15: Schema.CaseClass15[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass15.field1.schema)
      f2  <- gen(caseClass15.field2.schema)
      f3  <- gen(caseClass15.field3.schema)
      f4  <- gen(caseClass15.field4.schema)
      f5  <- gen(caseClass15.field5.schema)
      f6  <- gen(caseClass15.field6.schema)
      f7  <- gen(caseClass15.field7.schema)
      f8  <- gen(caseClass15.field8.schema)
      f9  <- gen(caseClass15.field9.schema)
      f10 <- gen(caseClass15.field10.schema)
      f11 <- gen(caseClass15.field11.schema)
      f12 <- gen(caseClass15.field12.schema)
      f13 <- gen(caseClass15.field13.schema)
      f14 <- gen(caseClass15.field14.schema)
      f15 <- gen(caseClass15.field15.schema)
    } yield caseClass15.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15)

  private def genCaseClass16[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, Z](
    caseClass16: Schema.CaseClass16[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass16.field1.schema)
      f2  <- gen(caseClass16.field2.schema)
      f3  <- gen(caseClass16.field3.schema)
      f4  <- gen(caseClass16.field4.schema)
      f5  <- gen(caseClass16.field5.schema)
      f6  <- gen(caseClass16.field6.schema)
      f7  <- gen(caseClass16.field7.schema)
      f8  <- gen(caseClass16.field8.schema)
      f9  <- gen(caseClass16.field9.schema)
      f10 <- gen(caseClass16.field10.schema)
      f11 <- gen(caseClass16.field11.schema)
      f12 <- gen(caseClass16.field12.schema)
      f13 <- gen(caseClass16.field13.schema)
      f14 <- gen(caseClass16.field14.schema)
      f15 <- gen(caseClass16.field15.schema)
      f16 <- gen(caseClass16.field16.schema)
    } yield caseClass16.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16)

  private def genCaseClass17[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, Z](
    caseClass17: Schema.CaseClass17[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass17.field1.schema)
      f2  <- gen(caseClass17.field2.schema)
      f3  <- gen(caseClass17.field3.schema)
      f4  <- gen(caseClass17.field4.schema)
      f5  <- gen(caseClass17.field5.schema)
      f6  <- gen(caseClass17.field6.schema)
      f7  <- gen(caseClass17.field7.schema)
      f8  <- gen(caseClass17.field8.schema)
      f9  <- gen(caseClass17.field9.schema)
      f10 <- gen(caseClass17.field10.schema)
      f11 <- gen(caseClass17.field11.schema)
      f12 <- gen(caseClass17.field12.schema)
      f13 <- gen(caseClass17.field13.schema)
      f14 <- gen(caseClass17.field14.schema)
      f15 <- gen(caseClass17.field15.schema)
      f16 <- gen(caseClass17.field16.schema)
      f17 <- gen(caseClass17.field17.schema)
    } yield caseClass17.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17)

  private def genCaseClass18[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, Z](
    caseClass18: Schema.CaseClass18[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass18.field1.schema)
      f2  <- gen(caseClass18.field2.schema)
      f3  <- gen(caseClass18.field3.schema)
      f4  <- gen(caseClass18.field4.schema)
      f5  <- gen(caseClass18.field5.schema)
      f6  <- gen(caseClass18.field6.schema)
      f7  <- gen(caseClass18.field7.schema)
      f8  <- gen(caseClass18.field8.schema)
      f9  <- gen(caseClass18.field9.schema)
      f10 <- gen(caseClass18.field10.schema)
      f11 <- gen(caseClass18.field11.schema)
      f12 <- gen(caseClass18.field12.schema)
      f13 <- gen(caseClass18.field13.schema)
      f14 <- gen(caseClass18.field14.schema)
      f15 <- gen(caseClass18.field15.schema)
      f16 <- gen(caseClass18.field16.schema)
      f17 <- gen(caseClass18.field17.schema)
      f18 <- gen(caseClass18.field18.schema)
    } yield caseClass18.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18)

  // scalafmt: { maxColumn = 200 }
  private def genCaseClass19[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, Z](
    caseClass19: Schema.CaseClass19[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass19.field1.schema)
      f2  <- gen(caseClass19.field2.schema)
      f3  <- gen(caseClass19.field3.schema)
      f4  <- gen(caseClass19.field4.schema)
      f5  <- gen(caseClass19.field5.schema)
      f6  <- gen(caseClass19.field6.schema)
      f7  <- gen(caseClass19.field7.schema)
      f8  <- gen(caseClass19.field8.schema)
      f9  <- gen(caseClass19.field9.schema)
      f10 <- gen(caseClass19.field10.schema)
      f11 <- gen(caseClass19.field11.schema)
      f12 <- gen(caseClass19.field12.schema)
      f13 <- gen(caseClass19.field13.schema)
      f14 <- gen(caseClass19.field14.schema)
      f15 <- gen(caseClass19.field15.schema)
      f16 <- gen(caseClass19.field16.schema)
      f17 <- gen(caseClass19.field17.schema)
      f18 <- gen(caseClass19.field18.schema)
      f19 <- gen(caseClass19.field19.schema)
    } yield caseClass19.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19)

  private def genCaseClass20[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, Z](
    caseClass20: Schema.CaseClass20[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass20.field1.schema)
      f2  <- gen(caseClass20.field2.schema)
      f3  <- gen(caseClass20.field3.schema)
      f4  <- gen(caseClass20.field4.schema)
      f5  <- gen(caseClass20.field5.schema)
      f6  <- gen(caseClass20.field6.schema)
      f7  <- gen(caseClass20.field7.schema)
      f8  <- gen(caseClass20.field8.schema)
      f9  <- gen(caseClass20.field9.schema)
      f10 <- gen(caseClass20.field10.schema)
      f11 <- gen(caseClass20.field11.schema)
      f12 <- gen(caseClass20.field12.schema)
      f13 <- gen(caseClass20.field13.schema)
      f14 <- gen(caseClass20.field14.schema)
      f15 <- gen(caseClass20.field15.schema)
      f16 <- gen(caseClass20.field16.schema)
      f17 <- gen(caseClass20.field17.schema)
      f18 <- gen(caseClass20.field18.schema)
      f19 <- gen(caseClass20.field19.schema)
      f20 <- gen(caseClass20.field20.schema)
    } yield caseClass20.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f20)

  private def genCaseClass21[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, Z](
    caseClass21: Schema.CaseClass21[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass21.field1.schema)
      f2  <- gen(caseClass21.field2.schema)
      f3  <- gen(caseClass21.field3.schema)
      f4  <- gen(caseClass21.field4.schema)
      f5  <- gen(caseClass21.field5.schema)
      f6  <- gen(caseClass21.field6.schema)
      f7  <- gen(caseClass21.field7.schema)
      f8  <- gen(caseClass21.field8.schema)
      f9  <- gen(caseClass21.field9.schema)
      f10 <- gen(caseClass21.field10.schema)
      f11 <- gen(caseClass21.field11.schema)
      f12 <- gen(caseClass21.field12.schema)
      f13 <- gen(caseClass21.field13.schema)
      f14 <- gen(caseClass21.field14.schema)
      f15 <- gen(caseClass21.field15.schema)
      f16 <- gen(caseClass21.field16.schema)
      f17 <- gen(caseClass21.field17.schema)
      f18 <- gen(caseClass21.field18.schema)
      f19 <- gen(caseClass21.field19.schema)
      f20 <- gen(caseClass21.field20.schema)
      f21 <- gen(caseClass21.field21.schema)
    } yield caseClass21.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f20, f21)

  private def genCaseClass22[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, Z](
    caseClass22: Schema.CaseClass22[A1, A2, A3, A4, A5, A6, A7, A8, A9, A10, A11, A12, A13, A14, A15, A16, A17, A18, A19, A20, A21, A22, Z]
  ): Gen[Sized, Z] =
    for {
      f1  <- gen(caseClass22.field1.schema)
      f2  <- gen(caseClass22.field2.schema)
      f3  <- gen(caseClass22.field3.schema)
      f4  <- gen(caseClass22.field4.schema)
      f5  <- gen(caseClass22.field5.schema)
      f6  <- gen(caseClass22.field6.schema)
      f7  <- gen(caseClass22.field7.schema)
      f8  <- gen(caseClass22.field8.schema)
      f9  <- gen(caseClass22.field9.schema)
      f10 <- gen(caseClass22.field10.schema)
      f11 <- gen(caseClass22.field11.schema)
      f12 <- gen(caseClass22.field12.schema)
      f13 <- gen(caseClass22.field13.schema)
      f14 <- gen(caseClass22.field14.schema)
      f15 <- gen(caseClass22.field15.schema)
      f16 <- gen(caseClass22.field16.schema)
      f17 <- gen(caseClass22.field17.schema)
      f18 <- gen(caseClass22.field18.schema)
      f19 <- gen(caseClass22.field19.schema)
      f20 <- gen(caseClass22.field20.schema)
      f21 <- gen(caseClass22.field21.schema)
      f22 <- gen(caseClass22.field22.schema)
    } yield caseClass22.construct(f1, f2, f3, f4, f5, f6, f7, f8, f9, f10, f11, f12, f13, f14, f15, f16, f17, f18, f19, f20, f21, f22)
  // scalafmt: { maxColumn = 120 }

  private def genGenericRecord(record: Schema.GenericRecord): Gen[Sized, ListMap[String, _]] =
    record.structure
      .foldLeft[Gen[Sized, ListMap[String, _]]](Gen.const(ListMap.empty)) {
        case (genListMap, field) =>
          for {
            listMap <- genListMap
            value   <- gen(field.schema)
          } yield listMap.updated(field.label, value)
      }

  private def genSequence[Z, A](seq: Schema.Sequence[Z, A, _]): Gen[Sized, Z] =
    Gen.oneOf(Gen.chunkOfN(2)(gen(seq.schemaA)), Gen.const(Chunk.empty)).map(seq.fromChunk(_))

  private def genMap[K, V](map: Schema.Map[K, V]): Gen[Sized, Map[K, V]] =
    Gen.oneOf(Gen.mapOfN(2)(gen(map.keySchema), gen(map.valueSchema)), Gen.const(Map.empty[K, V]))

  private def genSet[A](set: Schema.Set[A]): Gen[Sized, Set[A]] =
    Gen.oneOf(Gen.setOf(gen(set.elementSchema)), Gen.const(Set.empty[A]))

  private def genTransform[A, B, I](transform: Schema.Transform[A, B, I]): Gen[Sized, B] =
    gen(transform.schema).flatMap(a => transform.f(a).fold(_ => Gen.empty, (b: B) => Gen.const(b)))

  def genPrimitive[A](standardType: StandardType[A]): Gen[Sized, A] = {
    val gen = standardType match {
      case StandardType.UnitType              => Gen.unit
      case StandardType.StringType            => Gen.string
      case StandardType.BoolType              => Gen.boolean
      case StandardType.ByteType              => Gen.byte
      case StandardType.ShortType             => Gen.short
      case StandardType.IntType               => Gen.int
      case StandardType.LongType              => Gen.long
      case StandardType.FloatType             => Gen.float
      case StandardType.DoubleType            => Gen.double
      case StandardType.BinaryType            => Gen.chunkOf1(Gen.byte).map(_.toChunk)
      case StandardType.CharType              => Gen.char
      case StandardType.UUIDType              => Gen.uuid
      case StandardType.BigDecimalType        => Gen.double.map(new java.math.BigDecimal(_))
      case StandardType.BigIntegerType        => Gen.long.map(java.math.BigInteger.valueOf(_))
      case StandardType.DayOfWeekType         => Gen.dayOfWeek
      case StandardType.MonthType             => Gen.month
      case StandardType.MonthDayType          => Gen.monthDay
      case StandardType.PeriodType            => Gen.period
      case StandardType.YearType              => Gen.year
      case StandardType.YearMonthType         => Gen.yearMonth
      case StandardType.ZoneIdType            => Gen.zoneId
      case StandardType.ZoneOffsetType        => Gen.zoneOffset
      case StandardType.DurationType          => Gen.finiteDuration
      case StandardType.InstantType(_)        => Gen.instant
      case StandardType.LocalDateType(_)      => Gen.localDate
      case StandardType.LocalTimeType(_)      => Gen.localTime
      case StandardType.LocalDateTimeType(_)  => Gen.localDateTime
      case StandardType.OffsetTimeType(_)     => Gen.offsetTime
      case StandardType.OffsetDateTimeType(_) => Gen.offsetDateTime
      case StandardType.ZonedDateTimeType(_)  => Gen.zonedDateTime
    }

    gen.map(_.asInstanceOf[A])
  }

  private def genOptional[A](optional: Schema.Optional[A]): Gen[Sized, Option[A]] =
    Gen.option(gen(optional.schema))

  private def genFail[A](fail: Schema.Fail[A]): Gen[Sized, A] = {
    val _ = fail
    Gen.empty
  }

  private def genTuple[A, B](tuple: Schema.Tuple2[A, B]): Gen[Sized, (A, B)] =
    gen(tuple.left).zip(gen(tuple.right))

  private def genEither[A, B](either: Schema.Either[A, B]): Gen[Sized, scala.util.Either[A, B]] =
    Gen.either(gen(either.left), gen(either.right))

  private def genLazy[A](lazySchema: Schema.Lazy[A]): Gen[Sized, A] =
    Gen.suspend(gen(lazySchema.schema))

  private def genSchemaAstProduct(path: NodePath): Gen[Sized, MetaSchema.Product] =
    for {
      id       <- Gen.string(Gen.alphaChar).map(TypeId.parse)
      optional <- Gen.boolean
      fields <- Gen.chunkOf(
                 Gen
                   .string1(Gen.asciiChar)
                   .flatMap(name => genAst(path / name).map(fieldSchema => (name, fieldSchema)))
               )
    } yield MetaSchema.Product(id, path, fields, optional)

  private def genSchemaAstSum(path: NodePath): Gen[Sized, MetaSchema.Sum] =
    for {
      id       <- Gen.string(Gen.alphaChar).map(TypeId.parse)
      optional <- Gen.boolean
      fields <- Gen.chunkOf(
                 Gen
                   .string1(Gen.asciiChar)
                   .flatMap(name => genAst(path / name).map(fieldSchema => (name, fieldSchema)))
               )
    } yield MetaSchema.Sum(id, path, fields, optional)

  private def genSchemaAstValue(path: NodePath): Gen[Any, MetaSchema.Value] =
    for {
      formatter <- Gen.oneOf(
                    Gen.const(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    Gen.const(DateTimeFormatter.RFC_1123_DATE_TIME)
                  )
      valueType <- Gen.oneOf(
                    Gen.const(StandardType.UnitType),
                    Gen.const(StandardType.StringType),
                    Gen.const(StandardType.BoolType),
                    Gen.const(StandardType.ShortType),
                    Gen.const(StandardType.IntType),
                    Gen.const(StandardType.LongType),
                    Gen.const(StandardType.FloatType),
                    Gen.const(StandardType.BinaryType),
                    Gen.const(StandardType.CharType),
                    Gen.const(StandardType.UUIDType),
                    Gen.const(StandardType.BigDecimalType),
                    Gen.const(StandardType.BigIntegerType),
                    Gen.const(StandardType.DayOfWeekType),
                    Gen.const(StandardType.MonthType),
                    Gen.const(StandardType.MonthDayType),
                    Gen.const(StandardType.PeriodType),
                    Gen.const(StandardType.YearType),
                    Gen.const(StandardType.YearMonthType),
                    Gen.const(StandardType.ZoneIdType),
                    Gen.const(StandardType.ZoneOffsetType),
                    Gen.const(StandardType.DurationType),
                    Gen.const(StandardType.InstantType(formatter)),
                    Gen.const(StandardType.LocalDateType(formatter)),
                    Gen.const(StandardType.LocalTimeType(formatter)),
                    Gen.const(StandardType.LocalDateTimeType(formatter)),
                    Gen.const(StandardType.OffsetTimeType(formatter)),
                    Gen.const(StandardType.OffsetDateTimeType(formatter)),
                    Gen.const(StandardType.ZonedDateTimeType(formatter))
                  )
      optional <- Gen.boolean
    } yield MetaSchema.Value(valueType, path, optional)

  private def genSchemaAstDynamic(path: NodePath): Gen[Any, MetaSchema.Dynamic] =
    for {
      withSchema <- Gen.boolean
      optional   <- Gen.boolean
    } yield MetaSchema.Dynamic(withSchema, path, optional)

  private def genAst(path: NodePath): Gen[Sized, MetaSchema] =
    Gen.weighted(
      genSchemaAstProduct(path) -> 3,
      genSchemaAstSum(path)     -> 1,
      genSchemaAstValue(path)   -> 5,
      genSchemaAstDynamic(path) -> 1
    )
}
