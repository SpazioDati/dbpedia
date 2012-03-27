package org.dbpedia.extraction.server.util

import _root_.java.util.logging.Logger
import io.Source
import java.lang.IllegalArgumentException
import org.dbpedia.extraction.wikiparser.impl.wikipedia.Namespaces
import org.dbpedia.extraction.wikiparser.WikiTitle
import org.dbpedia.extraction.mappings._
import org.dbpedia.extraction.util.{WikiUtil, Language}
import scala.Serializable
import scala.collection.immutable.Map
import scala.collection.mutable
import scala.collection.mutable.HashMap
import java.io._
import org.dbpedia.extraction.server.Configuration
import org.dbpedia.extraction.ontology.OntologyNamespaces
import org.dbpedia.extraction.destinations.{DBpediaDatasets,Dataset}
import org.dbpedia.extraction.server.util.CreateMappingStats._
import java.net.{URLDecoder, URLEncoder}

/**
 * Script to gather statistics about mappings: how often they are used, which properties are used and for what mappings exist.
 * Take care of dump encodings. I had problems with the german redirects dump and had to delete all triples with a % sign.
 * There were only a few irrelevant templates, therefore it isn't a big deal.
 * 
 * It would be nice to unescape N-Triple encoding like \u1234 first, then use the string
 * and match it against regexes etc. But: doesn't work because of \" in string values.
 * 
 * TODO: even better - the extraction framework should be flexible and configurable enough that
 * it can write simpler formats besides N-Triples. This class would be MUCH simpler if it had
 * to read simple text files without N-Triples and URI-encoding.
 */
class CreateMappingStats(val language: Language)
{
    val logger = Logger.getLogger(classOf[CreateMappingStats].getName)

    val mappingStatsObjectFile = outputFile(new File("src/main/resources"), language)

    val ignoreListFileName = "src/main/resources/ignoreList_" + language.wikiCode + ".obj"
    val ignoreListTemplatesFileName = "src/main/resources/ignoreListTemplates_" + language.wikiCode + ".txt"
    val ignoreListPropertiesFileName = "src/main/resources/ignoreListProperties_" + language.wikiCode + ".txt"

    val percentageFileName = "src/main/resources/percentage." + language.wikiCode

    val encodedTemplateNamespacePrefix = doubleEncode(Namespaces.getNameForNamespace(language, WikiTitle.Namespace.Template) + ":", language)
    private val resourceNamespacePrefix = OntologyNamespaces.getResource("", language)

    private val ObjectPropertyTripleRegex = """<([^>]+)> <([^>]+)> <([^>]+)> .""".r
    private val DatatypePropertyTripleRegex = """<([^>]+)> <([^>]+)> "(.+?)"\S* .""".r

    /**
     * FIXME: A method like this should be shot. Just kill it. Delete all traces of its existence. JC 2012-03-25
     */
    def doubleDecode(string: String, lang: Language): String =
    {
        WikiUtil.wikiDecode(string, lang)
        // URLDecoder.decode(WikiUtil.wikiDecode(string, lang), "UTF-8")
    }

    /**
     * FIXME: A method like this should be shot. Just kill it. Delete all traces of its existence. JC 2012-03-25
     */
    def doubleEncode(string: String, lang: Language): String =
    {
        WikiUtil.wikiEncode(string, lang)
        // URLEncoder.encode(WikiUtil.wikiEncode(string, lang), "UTF-8")
    }

    def isTemplateNamespaceEncoded(template: String): Int =
    {
        if (template startsWith encodedTemplateNamespacePrefix) 1
        else if (template startsWith doubleDecode(encodedTemplateNamespacePrefix, language)) 0
        else -1
    }

    def getDecodedTemplateName(rawTemplate: String): String =
    {
        var templateName = ""
        if (isTemplateNamespaceEncoded(rawTemplate) == 1)
        {
            templateName = doubleDecode(rawTemplate.substring(encodedTemplateNamespacePrefix.length()), language)
        }
        else if (isTemplateNamespaceEncoded(rawTemplate) == 0)
        {
            val subLength = doubleDecode(encodedTemplateNamespacePrefix, language).length()
            templateName = rawTemplate.substring(subLength).replace("_", " ")
        }
        else
        {
            templateName = WikiUtil.wikiDecode(rawTemplate)
        }
        templateName
    }

    def countMappedStatistics(mappings: Map[String, ClassMapping], wikipediaStatistics: WikipediaStats) =
    {
        val startTime = System.currentTimeMillis()

        // Hold the overall statistics
        var statistics: Set[MappingStats] = Set() // new Set() with Serializable

        var isMapped: Boolean = false

        for ((rawTemplate, templateStats) <- wikipediaStatistics.templates)
        {
            val templateName: String = getDecodedTemplateName(rawTemplate)

            //mappings: el, en, pt decoded templates without _
            isMapped = checkMapping(templateName, mappings)
            var mappingStats: MappingStats = new MappingStats(templateStats, templateName)
            mappingStats.setTemplateMapped(isMapped)

            if (isMapped)
            {
                val mappedProperties = collectProperties(mappings.get(templateName).get)
                for (propName <- mappedProperties)
                {
                    mappingStats.setPropertyMapped(propName, true)
                }
            }
            statistics += mappingStats
        }

        var statsMap: Map[MappingStats, Int] = Map()
        for (mappingStat <- statistics)
        {
            statsMap += ((mappingStat, mappingStat.templateCount))
        }

        logger.fine("countMappedStatistics: " + (System.currentTimeMillis() - startTime) / 1000 + " s")
        statistics
    }


    def loadIgnorelist() =
    {
        if (new File(ignoreListFileName).isFile)
        {
            logger.fine("Loading serialized object from " + ignoreListFileName)
            val input = new ObjectInputStream(new FileInputStream(ignoreListFileName))
            val m = input.readObject()
            input.close()
            m.asInstanceOf[IgnoreList]
        }
        else
        {
            val ign: IgnoreList = new IgnoreList(language)
            ign
        }
    }

    def saveIgnorelist(ignoreList: IgnoreList)
    {
        val output = new ObjectOutputStream(new FileOutputStream(ignoreListFileName))
        output.writeObject(ignoreList)
        output.close()
        ignoreList.exportToTextFile(ignoreListTemplatesFileName, ignoreListPropertiesFileName)
    }

    private def getWikipediaStats(redirectsFile: File, infoboxPropsFile: File, templParamsFile: File, paramsUsageFile: File): WikipediaStats =
    {
        var templatesMap: mutable.Map[String, TemplateStats] = new HashMap() // "templateName" -> TemplateStats
        
        println("Reading redirects from " + redirectsFile)
        val redirects: Map[String, String] = loadTemplateRedirects(redirectsFile)
        println("  " + redirects.size + " redirects")
        
        println("Using Template namespace prefix " + encodedTemplateNamespacePrefix + " for language " + language.wikiCode)
        
        println("Counting templates in " + infoboxPropsFile)
        countTemplates(infoboxPropsFile, templatesMap, redirects)
        println("  " + templatesMap.size + " different templates")


        println("Loading property definitions from " + templParamsFile)
        propertyDefinitions(templParamsFile, templatesMap, redirects)

        println("Counting properties in " + paramsUsageFile)
        countProperties(paramsUsageFile, templatesMap, redirects)

        new WikipediaStats(redirects, templatesMap.toMap) // toMap makes it immutable
    }

    private def stripUri(fullUri: String): String =
    {
        fullUri.substring(resourceNamespacePrefix.length)
    }

    private def loadTemplateRedirects(fileName: File): Map[String, String] =
    {
        var redirects: mutable.Map[String, String] = new HashMap()
        for (line <- Source.fromFile(fileName, "UTF-8").getLines())
        {
            line match
            {
                case ObjectPropertyTripleRegex(subj, pred, obj) =>
                {
                    val templateName = stripUri(subj)
                    //TODO: adjust depending on encoding in redirects file
                    var templateNamespacePrefix = ""
                    if (language.wikiCode == "de" || language.wikiCode == "el" || language.wikiCode == "ru")
                    {
                        templateNamespacePrefix = doubleDecode(encodedTemplateNamespacePrefix, language)
                    }
                    else
                    {
                        templateNamespacePrefix = encodedTemplateNamespacePrefix
                    }
                    if (templateName startsWith templateNamespacePrefix)
                    {
                        redirects = redirects.updated(templateName, stripUri(obj))
                    }
                }
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match redirects syntax: " + line)
                case _ =>
            }
        }

        // resolve transitive closure
        for ((source, target) <- redirects)
        {
            var cyclePrevention: Set[String] = Set()
            var closure = target
            while (redirects.contains(closure) && !cyclePrevention.contains(closure))
            {
                closure = redirects.get(closure).get
                cyclePrevention += closure
            }
            redirects = redirects.updated(source, closure)
        }

        redirects.toMap // toMap makes immutable
    }

    /**
     * @param fileName name of file generated by InfoboxExtractor, e.g. infobox_properties_en.nt
     */
    private def countTemplates( fileName: File, resultMap: mutable.Map[String, TemplateStats], redirects: Map[String, String]): Unit =
    {
        // iterate through infobox properties
        // FIXME: close the source
        for (line <- Source.fromFile(fileName, "UTF-8").getLines())
        {
            line match
            {
                // if there is a wikiPageUsesTemplate relation
                case ObjectPropertyTripleRegex(subj, pred, obj) => if (pred contains "wikiPageUsesTemplate")
                {
                    val objName = stripUri(obj)
                    
                    // resolve redirect for *object*
                    val templateName = redirects.getOrElse(objName, objName)

                    // lookup the *object* in the resultMap, create a new TemplateStats object if not found,
                    // and increment templateCount
                    resultMap.getOrElseUpdate(templateName, new TemplateStats).templateCount += 1
                }
                case DatatypePropertyTripleRegex(_,_,_) => // ignore
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match property syntax: " + line)
            }
        }
    }

    private def propertyDefinitions(templParamsFile: File, resultMap: mutable.Map[String, TemplateStats], redirects: Map[String, String]): Unit =
    {
        // iterate through template parameters
        // FIXME: close the source
        for (line <- Source.fromFile(templParamsFile, "UTF-8").getLines())
        {
            line match
            {
                case DatatypePropertyTripleRegex(subj, pred, obj) =>
                {
                    val subjName = stripUri(subj)
                    val objName = unescapeNtriple(obj)
                    
                    // resolve redirect for *subject*
                    val templateName = redirects.getOrElse(subjName, subjName)

                    // lookup the *subject* in the resultMap
                    //skip the templates that are not found (they don't occurr in Wikipedia)
                    for (stats <- resultMap.get(templateName))
                    {
                        // add object to properties map with count 0
                        stats.properties.put(objName, 0)
                    }
                }
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match property syntax: " + line)
                case _ =>
            }
        }
    }

    private def countProperties(fileName: File, resultMap: mutable.Map[String, TemplateStats], redirects: Map[String, String]) : Unit =
    {
        // iterate through infobox test
        for (line <- Source.fromFile(fileName, "UTF-8").getLines())
        {
            line match
            {
                case DatatypePropertyTripleRegex(subj, pred, obj) =>
                {
                    val predName = stripUri(pred)
                    val objName = unescapeNtriple(obj)
                    
                    // resolve redirect for *predicate*
                    val templateName = redirects.getOrElse(predName, predName)

                    // lookup the *predicate* in the resultMap
                    // skip the templates that are not found (they don't occur in Wikipedia)
                    for(stats <- resultMap.get(templateName))
                    {
                        // lookup *object* in the properties map
                        //skip the properties that are not found with any count (they don't occurr in the template definition)
                        if (stats.properties.contains(objName))
                        {
                            // increment count in properties map
                            stats.properties.put(objName, stats.properties(objName) + 1)
                        }
                    }
                }
                case _ if line.nonEmpty => throw new IllegalArgumentException("line did not match countProperties syntax: " + line)
                case _ =>
            }
        }
    }

    private def checkMapping(template: String, mappings: Map[String, ClassMapping]) =
    {
        if (mappings.contains(template)) true
        else false
    }

    private def collectProperties(mapping: Object): Set[String] =
    {
        val properties = mapping match
        {
            case templateMapping: TemplateMapping => templateMapping.mappings.toSet.flatMap(collectPropertiesFromPropertyMapping)
            case conditionalMapping: ConditionalMapping => conditionalMapping.defaultMappings.toSet.flatMap(collectPropertiesFromPropertyMapping)
            case _ => Set[String]()
        }

        properties.filter(_ != null)
    }

    private def collectPropertiesFromPropertyMapping(propertyMapping: PropertyMapping): Set[String] = propertyMapping match
    {
        case simple: SimplePropertyMapping => Set(simple.templateProperty)
        case coord: GeoCoordinatesMapping => Set(coord.coordinates, coord.latitude, coord.longitude, coord.longitudeDegrees,
            coord.longitudeMinutes, coord.longitudeSeconds, coord.longitudeDirection,
            coord.latitudeDegrees, coord.latitudeMinutes, coord.latitudeSeconds, coord.latitudeDirection)
        case calc: CalculateMapping => Set(calc.templateProperty1, calc.templateProperty2)
        case combine: CombineDateMapping => Set(combine.templateProperty1, combine.templateProperty2, combine.templateProperty3)
        case interval: DateIntervalMapping => Set(interval.templateProperty)
        case intermediateNodeMapping: IntermediateNodeMapping => intermediateNodeMapping.mappings.toSet.flatMap(collectPropertiesFromPropertyMapping)
        case _ => Set()
    }

    def unescapeNtriple(value: String): String =
    {
        val sb = new java.lang.StringBuilder

        val inputLength = value.length
        var offset = 0

        while (offset < inputLength)
        {
            val c = value.charAt(offset)
            if (c != '\\') sb append c
            else
            {
                offset += 1
                val specialChar = value.charAt(offset)
                specialChar match
                {
                    case '"' => sb append '"'
                    case 't' => sb append '\t'
                    case 'r' => sb append '\r'
                    case '\\' => sb append '\\'
                    case 'n' => sb append '\n'
                    case 'u' =>
                    {
                        offset += 1
                        val codepoint = value.substring(offset, offset + 4)
                        val character = Integer.parseInt(codepoint, 16).asInstanceOf[Char]
                        sb append character
                        offset += 3
                    }
                    case 'U' =>
                    {
                        offset += 1
                        val codepoint = value.substring(offset, offset + 8)
                        val character = Integer.parseInt(codepoint, 16)
                        sb appendCodePoint character
                        offset += 7
                    }
                }
            }
            offset += 1
        }
        sb.toString
    }

}

object CreateMappingStats
{
    val logger = Logger.getLogger(classOf[CreateMappingStats].getName)

    /**
     * Hold template statistics
     * TODO: comment
     * @param templateCount apparently the number of pages that use the template. a page that uses
     * the template multiple times is counted only once.
     */
    class TemplateStats(var templateCount: Int = 0, val properties: mutable.Map[String, Int] = new HashMap()) extends Serializable
    {
        override def toString = "TemplateStats[count:" + templateCount + ",properties:" + properties.mkString(",") + "]"
    }

    /**
     * Contains the statistic of a Template
     */
    class MappingStats(templateStats: TemplateStats, val templateName: String) extends Ordered[MappingStats] with Serializable
    {
        var templateCount = templateStats.templateCount
        var isMapped: Boolean = false
        var properties: Map[String, (Int, Boolean)] = templateStats.properties.mapValues{freq => (freq, false)}.toMap

        def setTemplateMapped(mapped: Boolean)
        {
            isMapped = mapped
        }

        def setPropertyMapped(propertyName: String, mapped: Boolean)
        {
            val (freq, _) = properties.getOrElse(propertyName, (-1, false)) // -1 mapped but not allowed in the template
            properties = properties.updated(propertyName, (freq, mapped))
        }

        def getNumberOfProperties(ignoreList: IgnoreList) =
        {
            var counter: Int = 0
            for ((propName, _) <- templateStats.properties)
            {
                if (!ignoreList.isPropertyIgnored(templateName, propName))
                {
                    counter = counter + 1
                }
            }
            counter
        }

        def getNumberOfMappedProperties(ignoreList: IgnoreList) =
        {
            var numMPs: Int = 0
            for ((propName, (propCount, propIsMapped)) <- properties)
            {
                if (propIsMapped && propCount != -1 && !ignoreList.isPropertyIgnored(templateName, propName)) numMPs = numMPs + 1
            }
            numMPs
        }

        def getRatioOfMappedProperties(ignoreList: IgnoreList) =
        {
            var mappedRatio: Double = 0
            mappedRatio = getNumberOfMappedProperties(ignoreList).toDouble / getNumberOfProperties(ignoreList).toDouble
            mappedRatio
        }

        def getNumberOfPropertyOccurrences(ignoreList: IgnoreList) =
        {
            var numPOs: Int = 0
            for ((propName, (propCount, propIsMapped)) <- properties)
            {
                if (propCount != -1 && !ignoreList.isPropertyIgnored(templateName, propName)) numPOs = numPOs + propCount
            }
            numPOs
        }

        def getNumberOfMappedPropertyOccurrences(ignoreList: IgnoreList) =
        {
            var numMPOs: Int = 0
            for ((propName, (propCount, propIsMapped)) <- properties)
            {
                if (propIsMapped && propCount != -1 && !ignoreList.isPropertyIgnored(templateName, propName)) numMPOs = numMPOs + propCount
            }
            numMPOs
        }

        def getRatioOfMappedPropertyOccurrences(ignoreList: IgnoreList) =
        {
            var mappedRatio: Double = 0
            mappedRatio = getNumberOfMappedPropertyOccurrences(ignoreList).toDouble / getNumberOfPropertyOccurrences(ignoreList).toDouble
            mappedRatio
        }

        def compare(that: MappingStats) =
            this.templateCount - that.templateCount
    }

    // Hold template redirects and template statistics
    class WikipediaStats(val redirects: Map[String, String] = Map(), val templates: Map[String, TemplateStats] = Map()) extends Serializable
    {

        def checkForRedirects(mappingStats: Map[MappingStats, Int], mappings: Map[String, ClassMapping], lang: Language) =
        {
            val templateNamespacePrefix = Namespaces.getNameForNamespace(lang, WikiTitle.Namespace.Template) + ":"
            val mappedRedirrects = redirects.filterKeys(title => mappings.contains(WikiUtil.wikiDecode(title, lang).substring(templateNamespacePrefix.length())))
            mappedRedirrects.map(_.swap)
        }
    }
    
    
    def main(args: Array[String])
    {
        require (args != null && args.length >= 2, "need at least two args: input dir and output dir. may be followed by list of language codes.")
        
        val inputDir = new File(args(0))
        
        val outputDir = new File(args(1))
        
        val languages = 
          if (args.length > 2) getLanguages(args.slice(2, args.length))
          else Configuration.languages
        
        for (language <- languages) {
          
            val startTime = System.currentTimeMillis()
            
            logger.info("creating statistics for "+language.wikiCode)
            
            val serializeFile = outputFile(outputDir, language)
            
            // extracted by org.dbpedia.extraction.mappings.RedirectExtractor
            val redirectsDatasetFile = inputFile(inputDir, language, DBpediaDatasets.Redirects)
            // extracted by org.dbpedia.extraction.mappings.InfoboxExtractor
            val infoboxPropertiesDatasetFile = inputFile(inputDir, language, DBpediaDatasets.Infoboxes)
            // extracted by org.dbpedia.extraction.mappings.TemplateParameterExtractor
            val templateParametersDatasetFile = inputFile(inputDir, language, DBpediaDatasets.TemplateVariables)
            // extracted by org.dbpedia.extraction.mappings.InfoboxExtractor
            val infoboxTestDatasetFile = inputFile(inputDir, language, DBpediaDatasets.InfoboxTest)
            
            val createMappingStats = new CreateMappingStats(language)
    
            var wikiStats = createMappingStats.getWikipediaStats(redirectsDatasetFile, infoboxPropertiesDatasetFile, templateParametersDatasetFile, infoboxTestDatasetFile)
            logger.info("Serializing to " + serializeFile)
            serialize(serializeFile, wikiStats)
            
            logger.info("created statistics for "+language.wikiCode+" in "+(System.currentTimeMillis() - startTime) / 1000 + " s")
        }
        
    }
    
    /**
     * Use all remaining args as language codes or comma or whitespace separated lists of codes.
     */
    private def getLanguages(args : Array[String]) : Traversable[Language] =
    {
      for {
        arg <- args
        part <- arg.trim.split("[,\\s]")
        if (part.trim.nonEmpty)
      } yield Language.forCode(part.trim)
    }
    
    private def inputFile(baseDir : File, language : Language, dataset : Dataset) : File = {
      val langDir = new File(baseDir, language.filePrefix)
      new File(langDir, dataset.name + "_" + language.filePrefix + ".nt")
    }

    private def outputFile(baseDir : File, language : Language) : File = {
      new File(baseDir, "mappingstats_" + language.filePrefix + ".obj")
    }


    private def serialize(file: File, wikiStats: WikipediaStats)
    {
        val output = new ObjectOutputStream(new FileOutputStream(file))
        output.writeObject(wikiStats)
        output.close()
    }

    def deserialize(file: File): WikipediaStats =
    {
        val input = new ObjectInputStream(new FileInputStream(file))
        val m = input.readObject()
        input.close()
        m.asInstanceOf[WikipediaStats]
    }


}