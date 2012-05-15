package org.dbpedia.extraction.destinations

/**
 * Passes quads through to the target destination until a maximum number of
 * quads is reached. After that, additional quads are ignored.
 * 
 * TODO: This should be a mixin trait.
 * 
 * TODO: Rename. LimitingDestination might be better.
 */
class ThrottlingDestination( destination : Destination, limit : Int ) extends Destination
{
    private var count = 0
    
    override def write(g : Seq[Quad]) = if (count < limit)
    {
      var graph = g
      if (count + graph.length > limit) graph = graph.take(limit - count)
      destination.write(graph)
      count += graph.length
    }

    override def close = destination.close
}
