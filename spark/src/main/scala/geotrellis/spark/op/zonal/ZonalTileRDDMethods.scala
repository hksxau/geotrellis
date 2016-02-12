package geotrellis.spark.op.zonal

import geotrellis.raster.op.zonal._
import geotrellis.raster.op.stats._
import geotrellis.raster.histogram._
import geotrellis.raster._

import geotrellis.spark._
import geotrellis.spark.op._
import org.apache.spark.Partitioner
import org.apache.spark.rdd._

import spire.syntax.cfor._

trait ZonalTileRDDMethods[K] extends TileRDDMethods[K] {

  private def mergeMaps(a: Map[Int, Histogram[Int]], b: Map[Int, Histogram[Int]]) = {
    var res = a
    for ((k, v) <- b)
      res = res + (k ->
        (
          if (res.contains(k)) FastMapHistogram.fromHistograms(Seq(res(k), v))
          else v
        )
      )

    res
  }

  def zonalHistogram(zonesRasterRDD: RDD[(K, Tile)], partitioner: Option[Partitioner] = None): Map[Int, Histogram[Int]] = {
    partitioner
      .fold(self.join(zonesRasterRDD))(self.join(zonesRasterRDD, _))
      .map((t: (K, (Tile, Tile))) => ZonalHistogramInt(t._2._1, t._2._2))
      .fold(Map[Int, Histogram[Int]]())(mergeMaps)
  }

  def zonalPercentage(zonesRasterRDD: RDD[(K, Tile)], partitioner: Option[Partitioner] = None): RDD[(K, Tile)] = {
    val sc = self.sparkContext
    val zoneHistogramMap = zonalHistogram(zonesRasterRDD, partitioner)
    val zoneSumMap = zoneHistogramMap.map { case (k, v) => k -> v.getTotalCount }
    val bcZoneHistogramMap = sc.broadcast(zoneHistogramMap)
    val bcZoneSumMap = sc.broadcast(zoneSumMap)

    self.combineValues(zonesRasterRDD, partitioner) { case (tile, zone) =>
      val zhm = bcZoneHistogramMap.value
      val zsm = bcZoneSumMap.value

      val (cols, rows) = (tile.cols, tile.rows)

      val res = IntArrayTile.empty(cols, rows)

      cfor(0)(_ < rows, _ + 1) { row =>
        cfor(0)(_ < cols, _ + 1) { col =>
          val (v, z) = (tile.get(col, row), zone.get(col, row))

          val (count, zoneCount) = (zhm(z).getItemCount(v), zsm(z))

          res.set(col, row, math.round((count / zoneCount.toDouble) * 100).toInt)
        }
      }

      res: Tile
    }
  }

}
