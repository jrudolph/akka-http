package akka.http.scaladsl.model

import akka.http.scaladsl.model.headers.{ HttpEncodingRange, HttpEncodings, LanguageRange }
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalacheck.{ Arbitrary, Gen }
import Arbitrary.arbitrary

object HttpModelGenerators {
  val genMethodWithoutBody: Gen[HttpMethod] =
    Gen.oneOf(HttpMethods.GET, HttpMethods.HEAD, HttpMethods.DELETE, HttpMethods.TRACE)

  val genMethodWithBody: Gen[HttpMethod] =
    Gen.oneOf(HttpMethods.POST, HttpMethods.PUT, HttpMethods.PATCH, HttpMethods.OPTIONS)

  val genStrictTextEntity: Gen[HttpEntity.Strict] =
    for {
      text <- Gen.alphaNumStr
    } yield HttpEntity(text)

  val genDefaultTextEntity: Gen[HttpEntity.Default] =
    for {
      texts <- Gen.containerOf[Vector, ByteString](Gen.alphaNumStr.map(ByteString(_))) if texts.map(_.size).sum > 0 // default entity must not be empty
    } yield HttpEntity.Default(ContentTypes.`text/plain(UTF-8)`, texts.map(_.size).sum, Source(texts))

  val genChunkedTextEntity: Gen[HttpEntity.Chunked] =
    for {
      texts <- Gen.containerOf[Vector, ByteString](Gen.alphaNumStr.map(ByteString(_)))
    } yield HttpEntity.Chunked.fromData(ContentTypes.`text/plain(UTF-8)`, Source(texts))

  val genRequestEntity: Gen[RequestEntity] =
    Gen.oneOf(genStrictTextEntity, genDefaultTextEntity /*, genChunkedTextEntity*/ )

  val genMethodAndEntityWithBody: Gen[(HttpMethod, RequestEntity)] =
    for {
      method <- genMethodWithBody
      entity <- genRequestEntity
    } yield method -> entity

  val genMethodAndEntityWithoutBody: Gen[(HttpMethod, RequestEntity)] =
    for {
      method <- genMethodWithoutBody
    } yield method -> HttpEntity.Empty

  val genMethodAndEntity: Gen[(HttpMethod, RequestEntity)] =
    Gen.oneOf(genMethodAndEntityWithBody, genMethodAndEntityWithoutBody)

  // TODO: add more variety here
  val genUri: Gen[Uri] = Uri("/")

  val genAcceptEncoding: Gen[headers.`Accept-Encoding`] =
    for {
      encodings <- Gen.someOf(HttpEncodings.gzip, HttpEncodings.deflate).map(_.map(HttpEncodingRange(_)))
    } yield headers.`Accept-Encoding`(encodings)

  val genRequestHeaders: Gen[Seq[HttpHeader]] =
    // TODO: add more variety here
    Gen.someOf(genAcceptEncoding, headers.`Accept-Language`(LanguageRange.`*`))

  case class CustomValue(i: Int)
  val customAttribute = AttributeKey[CustomValue]("custom")
  val genCustomAttributeKV: Gen[(AttributeKey[_], _)] =
    for {
      value <- arbitrary[Int]
    } yield customAttribute -> CustomValue(value)

  val genAttributes: Gen[Map[AttributeKey[_], _]] =
    // TODO: add more variety here
    for {
      custom <- genCustomAttributeKV
    } yield Map(custom)

  val genAnyRequest = genRequest(genMethodAndEntity, genUri, genRequestHeaders, genAttributes)

  def genRequest(
    methodAndEntityGen: Gen[(HttpMethod, RequestEntity)],
    uriGen:             Gen[Uri],
    headersGen:         Gen[Seq[HttpHeader]],
    attributesGen:      Gen[Map[AttributeKey[_], _]]): Gen[HttpRequest] =
    for {
      (method, entity) <- methodAndEntityGen
      uri <- uriGen
      headers <- headersGen
      attributes <- attributesGen
    } yield new HttpRequest(
      method,
      uri,
      headers,
      attributes,
      entity,
      HttpProtocols.`HTTP/1.1`
    )
}
