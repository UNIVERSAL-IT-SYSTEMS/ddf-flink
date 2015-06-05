package io.flink.ddf.etl

import io.ddf.DDF
import io.ddf.content.Schema
import io.ddf.content.Schema.Column
import io.ddf.etl.{TransformationHandler => CoreTransformationHandler}
import io.ddf.exception.DDFException
import io.flink.ddf.content.FlinkRList
import org.apache.flink.api.scala.{DataSet, _}
import org.rosuda.REngine.Rserve.{RConnection, StartRserve}
import org.rosuda.REngine._

import scala.collection.JavaConversions._

class TransformationHandler(ddf: DDF) extends CoreTransformationHandler(ddf) {
  override def transformMapReduceNative(mapFuncDef: String, reduceFuncDef: String, mapsideCombine: Boolean = true): DDF = {

    // Prepare data as REXP objects
    val dfrdd = ddf.getRepresentationHandler.get(classOf[DataSet[_]], classOf[FlinkRList]).asInstanceOf[DataSet[FlinkRList]]

    // 1. map!
    val rMapped = dfrdd.map {
      partdf =>
        try {
          TransformationHandler.preShuffleMapper(partdf, mapFuncDef, reduceFuncDef, mapsideCombine)
        } catch {
          case aExc: DDFException =>
            throw aExc
          case rserveExc: org.rosuda.REngine.Rserve.RserveException =>
            throw new DDFException(rserveExc.getMessage, null)
          case e: Exception => throw new DDFException(e.getMessage, null)
        }
    }

    // 2. extract map key and shuffle!
    val grouped = TransformationHandler.doShuffle(rMapped)

    // 3. reduce!
    val rReduced = grouped.mapPartition {
      partdf =>
        try {
          TransformationHandler.postShufflePartitionMapper(partdf, reduceFuncDef)
        } catch {
          case aExc: DDFException => throw aExc
          case rserveExc: org.rosuda.REngine.Rserve.RserveException =>
            throw new DDFException(rserveExc.getMessage, null)
          case e: Exception => throw new DDFException(e.getMessage, null)
        }
    }.filter {
      partdf =>
        // mapPartitions after groupByKey may cause some empty partitions,
        // which will result in empty data.frame
        val dflist = partdf.asList()
        dflist.size() > 0 && dflist.at(0).length() > 0
    }

    // convert R-processed DF partitions back to BigR DataFrame
    val columnArr = TransformationHandler.RDataFrameToColumnList(rReduced)

    val newSchema = new Schema(ddf.getSchemaHandler.newTableName(), columnArr.toList)

    val manager = this.getManager
    val resultDDF = manager.newDDF(manager, rReduced, Array(classOf[DataSet[_]], classOf[REXP]), manager.getNamespace, null, newSchema)
    resultDDF
  }

  override def transformNativeRserve(transformExpression: String): DDF = {

    val dfrdd = ddf.getRepresentationHandler.get(classOf[DataSet[_]], classOf[FlinkRList]).asInstanceOf[DataSet[FlinkRList]]

    // process each DF partition in R
    val rMapped = dfrdd.map {
      partdf =>
        try {
          // check if Rserve is running, if not: start it
          if (!StartRserve.checkLocalRserve()) throw new RuntimeException("Unable to start Rserve")
          // one connection for each compute job
          val rconn = new RConnection()

          // send the df.partition to R process environment
          val dfvarname = "df.partition"

          val rList: RList = new RList(partdf.content, partdf.names)
          val dataFrame: REXP = REXP.createDataFrame(rList)

          rconn.assign(dfvarname, dataFrame)

          val expr = String.format("%s <- transform(%s, %s)", dfvarname, dfvarname, transformExpression)

          // mLog.info(">>>>>>>>>>>>.expr=" + expr.toString())

          // compute!
          TransformationHandler.tryEval(rconn, expr, errMsgHeader = "failed to eval transform expression")

          // transfer data to JVM
          val partdfres = rconn.eval(dfvarname)

          // uncomment this to print whole content of the df.partition for debug
          // rconn.voidEval(String.format("print(%s)", dfvarname))
          rconn.close()

          partdfres
        } catch {
          case e: DDFException =>
            throw new DDFException("Unable to perform NativeRserve transformation", e)
        }
    }

    // convert R-processed data partitions back to RDD[Array[Object]]
    val columnArr = TransformationHandler.RDataFrameToColumnList(rMapped)

    val newSchema = new Schema(ddf.getSchemaHandler.newTableName(), columnArr.toList)

    val manager = this.getManager

    val flinkRList = rMapped.map(rexp => FlinkRList(rexp.asList(), columnArr.map(_.getName)))

    val resultDDF = manager.newDDF(manager, flinkRList, Array(classOf[DataSet[_]], classOf[FlinkRList]), manager.getNamespace, null, newSchema)
    mLog.info(">>>>> adding ddf to manager: " + ddf.getName)
    resultDDF.getMetaDataHandler.copyFactor(this.getDDF)
    resultDDF
  }
}

object TransformationHandler {

  /**
   * Eval the expr in rconn, if succeeds return null (like rconn.voidEval),
   * if fails raise AdataoException with captured R error message.
   * See: http://rforge.net/Rserve/faq.html#errors
   */
  def tryEval(rconn: RConnection, expr: String, errMsgHeader: String) {
    rconn.assign(".tmp.", expr)
    val r = rconn.eval("r <- try(eval(parse(text=.tmp.)), silent=TRUE); if (inherits(r, 'try-error')) r else NULL")
    if (r.inherits("try-error")) throw new DDFException(errMsgHeader + ": " + r.asString())
  }

  /**
   * eval the R expr and return all captured output
   */
  def evalCaptureOutput(rconn: RConnection, expr: String): String = {
    rconn.eval("paste(capture.output(print(" + expr + ")), collapse='\\n')").asString()
  }

  def RDataFrameToColumnList(dataSet: DataSet[REXP]): Array[Column] = {
    val firstdf = dataSet.first(1).collect().head
    val trimmed: java.util.List[_] = firstdf._attr().asNativeJavaObject().asInstanceOf[java.util.List[_]]

    val names = trimmed.get(0).asInstanceOf[Array[String]]
    val columns = new Array[Column](firstdf.length)
    for (j ← 0 until firstdf.length()) {
      val ddfType = firstdf.asList().at(j) match {
        case v: REXPDouble => "DOUBLE"
        case v: REXPInteger => "INT"
        case v: REXPString => "STRING"
        case _ => throw new DDFException("Only support atomic vectors of type int|double|string!")
      }
      columns(j) = new Column(names(j), ddfType)
    }
    columns
  }

  /**
   * Perform map and mapsideCombine phase
   */
  def preShuffleMapper(partdf: FlinkRList, mapFuncDef: String, reduceFuncDef: String, mapsideCombine: Boolean): REXP = {
    // check if Rserve is running, if not: start it
    if (!StartRserve.checkLocalRserve()) throw new RuntimeException("Unable to start Rserve")
    // one connection for each compute job
    val rconn = new RConnection()

    println("after connecting, "+partdf.names.mkString(","))
    val rList: RList = new RList(partdf.content, partdf.names)
    val dataFrame: REXP = REXP.createDataFrame(rList)

    // send the df.partition to R process environment
    rconn.assign("df.partition", dataFrame)
    rconn.assign("mapside.combine", new REXPLogical(mapsideCombine))

    TransformationHandler.tryEval(rconn, "map.func <- " + mapFuncDef,
      errMsgHeader = "fail to eval map.func definition")
    TransformationHandler.tryEval(rconn, "combine.func <- " + reduceFuncDef,
      errMsgHeader = "fail to eval combine.func definition")

    // pre-amble to define internal functions
    // copied from: https://github.com/adatao/RClient/blob/master/io.pa/R/mapreduce.R
    // tests: https://github.com/adatao/RClient/blob/mapreduce/io.pa/inst/tests/test-mapreduce.r#L106
    // should consider some packaging to synchroncize code
    rconn.voidEval(
      """
        |#' Emit keys and values for map/reduce.
        |keyval <- function(key, val) {
        |  if (! is.atomic(key))
        |    stop(paste("keyval: key argument must be an atomic vector: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be one-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  nkey <- length(key)
        |  nval <- if (! is.null(nrow(val))) nrow(val) else length(val)
        |  if (nkey != nval)
        |    stop(sprintf("keyval: key and val arguments must match in length/nrow: %s != %s", nkey, nval))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-2d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' Emit a single key and value pair for map/reduce.
        |keyval.row <- function(key, val) {
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be a scala value, not n-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  if (length(key) != 1)
        |    stop(paste("keyval.row: key argument must be a scalar value: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(val)))
        |    stop(paste("keyval: val argument must be one-: dim(val) = ",
        |               paste(dim(val), collapse=" ")))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-1d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' does the kv pair have a adatao-defined attr?
        |is.adatao.kv <- function(kv) { (! is.null(attr(kv, "adatao-1d-kv-pair"))) | (! is.null(attr(kv, "adatao-2d-kv-pair"))) }
        |
        |#' should this be splitted?
        |is.adatao.1d.kv <- function(kv) { ! is.null(attr(kv, "adatao-1d-kv-pair")) }
        |
        |do.pre.shuffle <- function(partition, map.func, combine.func, mapside.combine = T, debug = F) {
        |  kv <- map.func(partition)
        |
        |  if (is.adatao.1d.kv(kv)) {
        |    # list of a single keyval object, with the serialized
        |    return(list(keyval.row(kv$key, serialize(kv$val, NULL))))
        |  }
        |
        |  val.bykey <- split(kv$val, f=kv$key)
        |  keys <- names(val.bykey)
        |
        |  result <- if (mapside.combine) {
        |    combine.result <- vector('list', length(keys))
        |    for (i in 1:length(val.bykey)) {
        |      kv <- combine.func(keys[[i]], val.bykey[[i]])
        |      combine.result[[i]] <- keyval.row(kv$key, serialize(kv$val, NULL))
        |    }
        |    # if (debug) print(combine.result)
        |    combine.result
        |  } else {
        |    kvlist.byrow <- vector('list', length(kv$key))
        |    z <- 1
        |    for (i in 1:length(keys)) {
        |      k <- keys[[i]]
        |      vv <- val.bykey[[i]]
        |      if (is.atomic(vv)) {
        |        for (j in 1:length(vv)) {
        |          kvlist.byrow[[z]] <- keyval.row(k, serialize(vv[[j]], NULL))
        |          z <- z + 1
        |        }
        |      } else {
        |        for (j in 1:nrow(vv)) {
        |          kvlist.byrow[[z]] <- keyval.row(k, serialize(vv[j, ], NULL))
        |          z <- z + 1
        |        }
        |      }
        |    }
        |    # if (debug) print(kvlist.byrow)
        |    kvlist.byrow
        |  }
        |  result
        |}
      """.stripMargin)

    // map!
    TransformationHandler.tryEval(rconn, "pre.shuffle.result <- do.pre.shuffle(df.partition, map.func, combine.func, mapside.combine, debug=T)",
      errMsgHeader = "fail to apply map.func to data partition")

    // transfer pre-shuffle result into JVM
    val result = rconn.eval("pre.shuffle.result")

    // we will another RConnection because we will now shuffle data
    rconn.close()

    result
  }

  /**
   * By now, whether mapsideCombine is true or false,
   * we both have each partition as a list of list(key=..., val=...)
   */
  //  def doShuffle(rMapped: RDD[REXP]): RDD[(String, Iterable[REXP])] = {
  def doShuffle(rMapped: DataSet[REXP]): DataSet[(String, Iterable[REXP])] = {
    val grouped = rMapped.flatMap {
      rexp =>
        rexp.asList().iterator.map {
          kv =>
            val kvl = kv.asInstanceOf[REXP].asList

            val (k, v) = (kvl.at("key").asString(), kvl.at("val"))
            (k, v)
        }
    }.groupBy(x => x._1).reduceGroup(x => (x.toSeq.head._1, x.map(_._2).toIterable)) //currently flink doesnt support group by key
    grouped
  }

  /**
   * serialize data to R, perform reduce,
   * then assemble each resulting partition as a data.frame of REXP in Java
   */
  def postShufflePartitionMapper(input: Iterator[(String, Iterable[REXP])], reduceFuncDef: String): Iterator[REXP] = {
    // check if Rserve is running, if not: start it
    if (!StartRserve.checkLocalRserve()) throw new RuntimeException("Unable to start Rserve")
    val rconn = new RConnection()

    // pre-amble
    // copied from: https://github.com/adatao/RClient/blob/master/io.pa/R/mapreduce.R
    // tests: https://github.com/adatao/RClient/blob/mapreduce/io.pa/inst/tests/test-mapreduce.r#L238
    // should consider some packaging to synchronize code
    rconn.voidEval(
      """
        |#' Emit keys and values for map/reduce.
        |keyval <- function(key, val) {
        |  if (! is.atomic(key))
        |    stop(paste("keyval: key argument must be an atomic vector: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be one-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  nkey <- length(key)
        |  nval <- if (! is.null(nrow(val))) nrow(val) else length(val)
        |  if (nkey != nval)
        |    stop(sprintf("keyval: key and val arguments must match in length/nrow: %s != %s", nkey, nval))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-2d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' Emit a single key and value pair for map/reduce.
        |keyval.row <- function(key, val) {
        |  if (! is.null(dim(key)))
        |    stop(paste("keyval: key argument must be a scala value, not n-dimensional: dim(key) = ",
        |               paste(dim(key), collapse=" ")))
        |  if (length(key) != 1)
        |    stop(paste("keyval.row: key argument must be a scalar value: ", paste(key, collapse=" ")))
        |  if (! is.null(dim(val)))
        |    stop(paste("keyval: val argument must be one-: dim(val) = ",
        |               paste(dim(val), collapse=" ")))
        |  kv <- list(key=key, val=val);
        |  attr(kv, "adatao-1d-kv-pair") <- T;
        |  kv
        |}
        |
        |#' does the kv pair have a adatao-defined attr?
        |is.adatao.kv <- function(kv) { (! is.null(attr(kv, "adatao-1d-kv-pair"))) | (! is.null(attr(kv, "adatao-2d-kv-pair"))) }
        |
        |#' should this be splitted?
        |is.adatao.1d.kv <- function(kv) { ! is.null(attr(kv, "adatao-1d-kv-pair")) }
        |
        |# flatten the reduced kv pair.
        |flatten.kvv <- function(rkv) {
        |  if (length(rkv$val) > 1) {
        |    row <- vector('list', length(rkv$val) + 1)
        |    row[1] <- rkv$key
        |    row[2:(length(rkv$val)+1)] <- rkv$val
        |    names(row) <- c("key", names(rkv$val))
        |    row
        |  } else {
        |    rkv
        |  }
        |}
        |
        |#' bind together list of values from the same keys as rows of a data.frame
        |rbind.vv <- function(vvlist) {
        |  df <- do.call(rbind.data.frame, vvlist)
        |  if (length(vvlist) > 0) {
        |    head <- vvlist[[1]]
        |    if ( is.null(names(head)) ) {
        |      if (length(head) == 1) {
        |        names(df) <- c("val")
        |      } else {
        |        names(df) <- Map(function(x){ paste("val", x, sep="") }, 1:length(head))
        |      }
        |    }
        |  }
        |  df
        |}
        |
        |handle.reduced.kv <- function(rkv) {
        |  if (is.adatao.1d.kv(rkv)) {
        |    row <- flatten.kvv(rkv)
        |    row
        |  } else if (is.adatao.kv(rkv)) {
        |    df <- rkv$val
        |    df$key <- rkv$key
        |    df
        |  } else {
        |    NULL
        |  }
        |}
      """.stripMargin)

    TransformationHandler.tryEval(rconn, "reduce.func <- " + reduceFuncDef,
      errMsgHeader = "fail to eval reduce.func definition")

    rconn.voidEval("reductions <- list()")
    rconn.voidEval("options(stringsAsFactors = F)")

    // we do this in a loop because each of the seqv could potentially be very large
    input.zipWithIndex.foreach {
      case ((k: String, seqv: Seq[_]), i: Int) =>

        // send data to R to compute reductions
        rconn.assign("idx", new REXPInteger(i))
        rconn.assign("reduce.key", k)
        rconn.assign("reduce.serialized.vvlist", new REXPList(new RList(seqv)))

        // print to Rserve log
//        rconn.voidEval("print(paste('====== processing key = ', reduce.key))")

        TransformationHandler.tryEval(rconn, "reduce.vvlist <- lapply(reduce.serialized.vvlist, unserialize)",
          errMsgHeader = "fail to unserialize shuffled values for key = " + k)

        TransformationHandler.tryEval(rconn, "reduce.vv <- rbind.vv(reduce.vvlist)",
          errMsgHeader = "fail to merge (using rbind.vv) shuffled values for key = " + k)

        // reduce!
        TransformationHandler.tryEval(rconn, "reduced.kv <- reduce.func(reduce.key, reduce.vv)",
          errMsgHeader = "fail to apply reduce func to data partition")

        // flatten the nested val list if needed
        TransformationHandler.tryEval(rconn, "reduced <- handle.reduced.kv(reduced.kv)",
          errMsgHeader = "malformed reduce.func output, please run mapreduce.local to test your reduce.func")

        // assign reduced item to reductions list
        rconn.voidEval("if (!is.null(reduced)) { reductions[[idx+1]] <- reduced } ")
    }

    // bind the reduced rows together, it contains rows of the resulting BigDataFrame
    TransformationHandler.tryEval(rconn, "reduced.partition <- do.call(rbind.data.frame, reductions)",
      errMsgHeader = "fail to use rbind.data.frame on reductions list, reduce.func cannot be combined as a BigDataFrame")

    // remove weird row names
    rconn.voidEval("rownames(reduced.partition) <- NULL")

    // transfer reduced data back to JVM
    val result = rconn.eval("reduced.partition")

    // print to Rserve log
//    rconn.voidEval("print('==== reduce phase completed')")

    // done R computation for this partition
    rconn.close()

    // wrap it on a Iterator to satisfy mapPartitions
    Iterator.single(result)
  }
}

