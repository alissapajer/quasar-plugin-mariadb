/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.plugin.mariadb.datasource

import quasar.plugin.mariadb.{HI, MariaDbHygiene}

import scala._, Predef._
import scala.collection.immutable.Map
import java.lang.Throwable

import cats.Defer
import cats.data.NonEmptyList
import cats.effect.{Bracket, Resource}
import cats.implicits._

import doobie._
import doobie.enum.JdbcType
import doobie.implicits._

import quasar.api.ColumnType
import quasar.connector.MonadResourceErr
import quasar.connector.datasource.{LightweightDatasourceModule, Loader}
import quasar.lib.jdbc._
import quasar.lib.jdbc.datasource._

import org.slf4s.Logger

private[datasource] object MariaDbDatasource {
  val DefaultResultChunkSize: Int = 4096

  def apply[F[_]: Bracket[?[_], Throwable]: Defer: MonadResourceErr](
      xa: Transactor[F],
      discovery: JdbcDiscovery,
      log: Logger)
      : LightweightDatasourceModule.DS[F] = {

    val maskInterpreter =
      MaskInterpreter(MariaDbHygiene) { (table, schema) =>
        discovery.tableColumns(table.asIdent, schema.map(_.asIdent))
          .map(m =>
            if (m.jdbcType == JdbcType.Bit && m.vendorType == Mapping.TINYINT)
              Some(m.name -> ColumnType.Boolean)
            else
              Mapping.MariaDbColumnTypes.get(m.vendorType)
                .orElse(Mapping.JdbcColumnTypes.get(m.jdbcType))
                .tupleLeft(m.name))
          .unNone
          .compile.to(Map)
      }

    val loader =
      JdbcLoader(xa, discovery, MariaDbHygiene) {
        RValueLoader[HI](Slf4sLogHandler(log), DefaultResultChunkSize, MariaDbRValueColumn)
          .compose(maskInterpreter.andThen(Resource.liftF(_)))
      }

    JdbcDatasource(
      xa,
      discovery,
      MariaDbDatasourceModule.kind,
      NonEmptyList.one(Loader.Batch(loader)))
  }
}
