package eu.inn.samza.mesos.mapping

trait ResourceMappingStrategy {
  def mapResources[R <% ResourceHolder, X](resourceHolders: Iterable[R],
                                           objects: Set[X],
                                           constraints: ResourceConstraints): Map[R, Set[X]]

  def satisfies(holder: ResourceHolder, constraints: ResourceConstraints): Boolean = {
    constraints.attributes.forall(c => holder.getAttribute(c._1, "").matches(c._2)) &&
      constraints.resources.forall(c => holder.getResource(c._1, 0.0) >= c._2)
  }
}