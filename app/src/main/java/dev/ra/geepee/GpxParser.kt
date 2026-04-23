package dev.ra.geepee

import android.util.Xml
import java.io.InputStream
import org.xmlpull.v1.XmlPullParser

object GpxParser {
    fun parse(inputStream: InputStream): List<List<GeoPoint>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(inputStream, null)
        }

        val trackSegments = mutableListOf<List<GeoPoint>>()
        var currentTrackSegment: MutableList<GeoPoint>? = null
        val routePoints = mutableListOf<GeoPoint>()
        val waypointPoints = mutableListOf<GeoPoint>()

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkseg" -> currentTrackSegment = mutableListOf()
                    "trkpt" -> currentTrackSegment?.add(readPoint(parser))
                    "rtept" -> routePoints += readPoint(parser)
                    "wpt" -> waypointPoints += readPoint(parser)
                }

                XmlPullParser.END_TAG -> if (parser.name == "trkseg") {
                    currentTrackSegment
                        ?.takeIf { it.size >= 2 }
                        ?.let(trackSegments::add)
                    currentTrackSegment = null
                }
            }
            eventType = parser.next()
        }

        if (trackSegments.isNotEmpty()) {
            return trackSegments
        }
        if (routePoints.size >= 2) {
            return listOf(routePoints)
        }
        if (waypointPoints.size >= 2) {
            return listOf(waypointPoints)
        }

        throw IllegalArgumentException("No track or route points were found in the GPX file.")
    }

    private fun readPoint(parser: XmlPullParser): GeoPoint {
        val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
        val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
        require(lat != null && lon != null) {
            "Encountered a GPX point without valid latitude and longitude."
        }
        return GeoPoint(lat = lat, lon = lon)
    }
}
