package worker

/**
  * @author Maksim Ochenashko
  */
object HexStringUtil {

  def string2hex(str: String): String =
    str.map(_.toInt.toHexString).mkString

  def hex2string(hex: String): String =
    hex.sliding(2, 2).map(Integer.parseInt(_, 16).toChar).mkString

}
