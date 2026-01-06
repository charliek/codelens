package sample

// This file intentionally has ktlint violations for testing purposes

fun badFunction( x:Int,y:Int ){
    val z=x+y
    if(z>0){
        println( "result" )
    }
}

class BadClass{
    val name:String="test"
    fun method( ){
        println("hello")
    }
}
