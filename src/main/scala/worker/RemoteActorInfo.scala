package worker

/**
  * @author Maksim Ochenashko
  */
private[worker] class RemoteActorInfo(val path: String) {

  val hexName: String = HexStringUtil.string2hex(path)

}

private[worker] object RemoteActorInfo {

  def apply(path: String): RemoteActorInfo =
    new RemoteActorInfo(path)

  def unapply(value: RemoteActorInfo): Option[(String, String)] =
    Some((value.path, value.hexName))

}