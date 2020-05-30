package com.example.myapplication.Activity

import android.content.Intent
import android.location.Address
import android.location.Geocoder
import android.os.AsyncTask
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.util.Log
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBMapper
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.DynamoDBScanExpression
import com.amazonaws.mobileconnectors.dynamodbv2.dynamodbmapper.PaginatedList
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient
import com.example.myapplication.Data.*
import com.example.myapplication.R
import com.koushikdutta.ion.Ion
import java.io.DataInputStream
import java.net.Socket
import java.util.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

class LoadingActivity : AppCompatActivity() {

    //AWS
    var dynamoDBMapper: DynamoDBMapper?= null
    var ddb : AmazonDynamoDBClient?= null
    lateinit var credentials: CognitoCachingCredentialsProvider

    //Data
    var martDataArray:PaginatedList<MartData> ?= null
    var eventDataArray:PaginatedList<EventData> ?= null
    var itemDataArray:PaginatedList<ProductData> ?= null
    var martDataLocation:ArrayList<MyLocation> = arrayListOf()
    var eventData:ArrayList<EventItem> = arrayListOf()
    var product:HashMap<String,Product> = hashMapOf()


    //Handler
    lateinit var handler:Handler

    //Handler Message
    var GET_DATA = 1111
    var DATA_TO_LOCATION = 2222

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_loading)
        //아마존 스레드
        init()
    }

    fun init(){
        //아마존에 연결
        credentials = CognitoCachingCredentialsProvider(this,"ap-northeast-2:1140fa47-3059-4bdb-a382-25735d00f34d", Regions.AP_NORTHEAST_2)
        ddb = AmazonDynamoDBClient(credentials)
        ddb!!.setRegion((Region.getRegion(Regions.AP_NORTHEAST_2)))
        Log.d("아마존",ddb.toString())
        dynamoDBMapper = DynamoDBMapper.builder().dynamoDBClient(ddb).build()


        //python 서버 연결
//        connectPython()

        //AsyncTask를 통해 데이터를 가져옴
        val AWSAsyncTask = AWSAsyncTask()
//        AWSAsyncTask.execute()

//        //핸들러 생성
        handler = Handler(Handler.Callback {
            //스레드 작업이 끝나면 할 것
            when(it.arg1){
                GET_DATA->{
                    //데이터를 다 불러옴
                    Log.d("스레드","GET_DATA")
                    DataConvertInit()
                    DatatoProduct()
                    StringtoImage()
                }
                DATA_TO_LOCATION->{
                    //데이터를 경도 위도 좌표로 변환
                    Log.d("스레드",martDataLocation.size.toString())
                    //모든 데이터를 받아왔으므로 액티비티 전환
                    val intent = Intent(this, MainActivity::class.java)
                    intent.putExtra("MART_DATA",martDataLocation)
                    intent.putExtra("EVENT_DATA",eventData)
                    intent.putExtra("PRODUCT_DATA",product)
                    Log.d("아마존","인텐트 변경")
//                    startActivity(intent)
                }
            }
            return@Callback true
        })
    }

    //python server
    fun connectPython(){
        Thread(object: Runnable {
            override fun run() {
                //TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
                Log.d("서버","연결중")
                var socket = Socket("192.168.0.6",35357)
                Log.d("서버",socket.toString())

                var dis = DataInputStream(socket.getInputStream())

//                while(true){
//                    var line = dis.read() as Int
//                    Log.d("서버","서버에서 받은 값 "+line.toString())
//
//                    if(line == 99){
//                        Log.d("서버","종료")
//                        socket.close()
//                        break
//                    }
//                }
            }

        }).start()
    }

    fun DatatoProduct(){
        for(item in itemDataArray!!){
            product[item.name] = Product(item.img,item.name,item.number,item.price,item.category)
        }
    }

    fun StringtoImage(){
        //이미지로 변환해주는 함수
        for(event in eventDataArray!!){
            var bit = Ion.with(this).load("http:"+event.getEventImg()).asBitmap().get()
            eventData.add(EventItem(event.getEventName(), bit))
        }

        //핸들러 메세지
        var message = handler.obtainMessage()
        message.arg1 = DATA_TO_LOCATION
        handler.sendMessage(message)
    }

    fun DataConvertInit(){
        //위도 경도로 변환하기 위한 함수
        var convert1 = DataConvert(martDataArray!!,0,martDataArray!!.size/2)
        var convert2 = DataConvert(martDataArray!!,martDataArray!!.size/2,martDataArray!!.size)

        var futureTask1 = FutureTask<ArrayList<MyLocation>>(convert1)
        var futureTask2 = FutureTask<ArrayList<MyLocation>>(convert2)

        var executor = Executors.newFixedThreadPool(2)

        //executorService에 보내서 실행한다
        executor.submit(futureTask1)
        executor.submit(futureTask2)


        while(true){
            if(futureTask1.isDone && futureTask2.isDone){
                //모두 종료된 경우
                martDataLocation.addAll(futureTask1.get())
                martDataLocation.addAll(futureTask2.get())
                break
            }
        }
    }

    inner class DataConvert:Callable<ArrayList<MyLocation>>{

        var data:PaginatedList<MartData>
        var start:Int
        var end:Int

        constructor(data:PaginatedList<MartData>,start:Int,end:Int){
            this.data = data
            this.start = start
            this.end = end
        }

        override fun call(): ArrayList<MyLocation> {
           // TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            var arrayData:ArrayList<MyLocation> = arrayListOf()//반환할 값

            var geocoder = Geocoder(applicationContext,Locale.getDefault())
            for(index in start until end){
                var addresses = arrayListOf<Address>()
                addresses = geocoder.getFromLocationName(data[index].getMartRoad(),1) as ArrayList<Address>

                if(addresses.size == 0){
                    arrayData.add(MyLocation(0.0,0.0,data[index].getMartName()))
                }else{
                    var latitude = addresses.get(0).latitude
                    var longitude = addresses.get(0).longitude
                    arrayData.add(MyLocation(latitude,longitude,data[index].getMartName()))
                }
            }
            return arrayData
        }
    }

    inner class AWSAsyncTask: AsyncTask<Void,Void,Int>(){
        override fun doInBackground(vararg p0: Void?): Int {
           // TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
            martDataArray = dynamoDBMapper!!.scan(MartData::class.java, DynamoDBScanExpression())
            eventDataArray = dynamoDBMapper!!.scan(EventData::class.java,DynamoDBScanExpression())
            itemDataArray = dynamoDBMapper!!.scan(ProductData::class.java,DynamoDBScanExpression())
            return 0
        }

        override fun onPostExecute(result: Int?) {
            super.onPostExecute(result)
            Log.d("스레드","onPOstExecute")
            var message = handler.obtainMessage()
            message.arg1 = GET_DATA
            handler.sendMessage(message)
        }
    }
}
