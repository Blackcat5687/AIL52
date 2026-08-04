package com.notekeep.local.graph

enum class GraphNodeType { NOTE, TAG, LABEL }

data class GraphNode(
    val id: String,
    val label: String,
    val type: GraphNodeType,
    val noteId: Long? = null,
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var degree: Int = 0,
    /** true while the user is dragging this node, or after they've dropped it in place. */
    var fixed: Boolean = false
) {
    val isTag: Boolean get() = type == GraphNodeType.TAG
}

data class GraphEdge(
    val sourceId: String,
    val targetId: String
)

data class GraphGroup(val query: String, val color: Int)

/**
 * Places nodes on the vertices of a honeycomb (hex) lattice, spiraling outward from the center,
 * so the graph starts out looking like a cluster of connected hexagons/a beehive rather than a
 * random scatter. This is only the *starting* position - the force simulation (spring links +
 * repulsion, both configurable from the graph settings) takes over immediately after and lets
 * nodes drift and settle like objects connected by elastic bands in a fluid.
 */
private fun applyHoneycombLayout(nodeList: List<GraphNode>) {
    if (nodeList.isEmpty()) return

    val cellRadius = 90f
    // axial hex directions, one step to each of the 6 neighboring lattice points
    val directions = listOf(
        1f to 0f, 0.5f to 0.8660254f, -0.5f to 0.8660254f,
        -1f to 0f, -0.5f to -0.8660254f, 0.5f to -0.8660254f
    )

    // generate hex-lattice points in rings around the origin (ring 0 = just the center point)
    val points = mutableListOf(0f to 0f)
    var ring = 1
    while (points.size < nodeList.size) {
        var (x, y) = directions[4].let { (dx, dy) -> dx * cellRadius * ring to dy * cellRadius * ring }
        for (side in 0 until 6) {
            val (dx, dy) = directions[side]
            repeat(ring) {
                points.add(x to y)
                x += dx * cellRadius
                y += dy * cellRadius
            }
        }
        ring++
    }

    nodeList.forEachIndexed { index, node ->
        val (px, py) = points[index]
        node.x = 400f + px
        node.y = 400f + py
    }
}

class GraphData(
    val nodes: MutableList<GraphNode>,
    val edges: MutableList<GraphEdge>
) {
    private val indexById = nodes.associateBy { it.id }.let { HashMap(it) }

    fun nodeById(id: String): GraphNode? = indexById[id]

    companion object {
        /** Builds a graph connecting each note to the #tags found inside it and to any labels (categories) assigned to it. */
        fun build(
            notes: List<com.notekeep.local.data.Note>,
            hideOrphans: Boolean,
            includeTags: Boolean = true,
            labels: List<com.notekeep.local.data.Label> = emptyList(),
            noteLabelPairs: List<Pair<Long, Long>> = emptyList()
        ): GraphData {
            val nodes = LinkedHashMap<String, GraphNode>()
            val edges = mutableListOf<GraphEdge>()
            val degreeCount = HashMap<String, Int>()

            val labelById = labels.associateBy { it.id }
            val labelIdsByNote = noteLabelPairs.groupBy({ it.first }, { it.second })

            for (note in notes) {
                val tags = note.extractTags()
                val noteLabelIds = labelIdsByNote[note.id].orEmpty()
                if (tags.isEmpty() && noteLabelIds.isEmpty() && hideOrphans) continue

                val noteNodeId = "note_${note.id}"
                val label = note.title.ifBlank {
                    note.content.take(18).ifBlank { "بدون عنوان" }
                }
                nodes.getOrPut(noteNodeId) {
                    GraphNode(id = noteNodeId, label = label, type = GraphNodeType.NOTE, noteId = note.id)
                }

                if (includeTags) {
                    for (tag in tags) {
                        val tagNodeId = "tag_$tag"
                        nodes.getOrPut(tagNodeId) {
                            GraphNode(id = tagNodeId, label = tag, type = GraphNodeType.TAG)
                        }
                        edges.add(GraphEdge(noteNodeId, tagNodeId))
                        degreeCount[noteNodeId] = (degreeCount[noteNodeId] ?: 0) + 1
                        degreeCount[tagNodeId] = (degreeCount[tagNodeId] ?: 0) + 1
                    }
                }

                for (labelId in noteLabelIds) {
                    val labelEntity = labelById[labelId] ?: continue
                    val labelNodeId = "label_$labelId"
                    nodes.getOrPut(labelNodeId) {
                        GraphNode(id = labelNodeId, label = labelEntity.name, type = GraphNodeType.LABEL)
                    }
                    edges.add(GraphEdge(noteNodeId, labelNodeId))
                    degreeCount[noteNodeId] = (degreeCount[noteNodeId] ?: 0) + 1
                    degreeCount[labelNodeId] = (degreeCount[labelNodeId] ?: 0) + 1
                }
            }

            val nodeList = nodes.values.toMutableList()
            nodeList.forEach { it.degree = degreeCount[it.id] ?: 0 }

            applyHoneycombLayout(nodeList)

            return GraphData(nodeList, edges)
        }
    }
}
