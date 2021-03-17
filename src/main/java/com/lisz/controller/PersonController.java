package com.lisz.controller;

import com.lisz.model.Person;
import com.lisz.service.PersonService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.WebSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Random;
import java.util.stream.IntStream;

// 注解式、函数式 SpringCloud Gateway
/*
Netty先接受请求，交给Controller，Controller return 的结果由Netty推给用户
 */
@RestController
@RequestMapping("/person")
public class PersonController {
	@Autowired
	private PersonService personService;

	@GetMapping("")
	public Mono<Object> get(){
		System.out.println("Controller Thread: " + Thread.currentThread().getName());
		System.out.println("---------  1");
		// async, 开了新的线程，这个结果还没拿到，数据就return回去了
		Mono<Object> mono = Mono.create(sink -> {
			System.out.println("Controller Thread: " + Thread.currentThread().getName());
			sink.success(personService.getPerson()); // 组装数据序列，数据序列组装好了之后扔到sink里，不是最先被调用的，最先被调用的是subscribe，观察者、被观察者建立好关联关系。第三步
		})
		.doOnSubscribe(sub -> {
			System.out.println("Controller Thread: " + Thread.currentThread().getName());
			System.out.println("aaa"); // 率先执行，先关注才能拿到数据，订阅数据 第一步
		})
		.doOnNext(data -> { // 有数据了做什么 第二步
			System.out.println("Controller Thread: " + Thread.currentThread().getName());
			System.out.println("data: " + data);
		})
		.doOnSuccess(onSuccess -> { // 全部完成之后 最后一步
			System.out.println("Controller Thread: " + Thread.currentThread().getName());
			System.out.println("onSuccess");
		});
		System.out.println("---------  2");
		// 即使return的时候是null，在Netty容器里，一看她是一个Mono的或者Flux的，则表示她是一个数据序列
		// 数据序列的产生是基于"响应式"或者"观察者模式"的。
		// Return的时候 mono还没有被组装好，但是空的也可以，先return回容器里去，什么时候它有数据了就会吱一声，数据能用了再return到前端
		// Netty容器阻塞了，也就是说在return之后还做了一些事情
		// return 跟进去会发现进到了：org.springframework.web.reactive.result.method.InvocableHandlerMethod 调用到容器层了，知道是空值，但是没抛异常
		// doOnSubscribe、doOnNext、sink.success、doOnSuccess根本不是在Controller里面做的，而是在Mono return回到Netty Web容器去之后被执行的
		// 感觉就是把Controller释放掉了，在Controller里面异步获取数据，上面的几个lambda表达式全部都是回调。
		// 得到一个包装数据序列 ——> 包含特征 --> return给容器 --> 调用以上的回调
		// 看起来像是异步，其实是在容器里阻塞了，因为其中有个Mono.just(...);还是阻塞的，只不过没发生在Controller曾，所以两次的System.out.println率先被打印了
		// 这么看真的有点像双重判断的懒汉式单例模式中，INSTANCE不加volatile的有指令重排序的执行流程啊！！
		// 跟到最后会到Operators.complete(), 其中state == 2 ，也就是：HAS_REQUEST_NO_VALUE，请求来了，但是还没结果。这里有个for (; ; ) {...}等待结果时就卡在这里
		// Controller中的线程跟容器里的业务线程应该是一个线程，猜的，因为没看到什么线程池
		return mono;
	}

	@GetMapping("/abc")
	public Mono<Object> get2(String name){
		System.out.println("name: " + name);
		return Mono.just("haha");
	}

	@GetMapping("/aaa")
	// ServerHttpRequest 是 webFlux中特有的，跟SpringMVC里的ServletRequest不一样
	// 不一定用得到，但是拓展思维，了解现在比较前沿的技术框架是怎么构架出来的，自己是否可以写服务器？不可能，太难啦。生产环境就是SpringCloud Gateway，不会WebFlux的话看不太懂SpringCloud Gateway（基本全是函数式）
	// IO密集度比较高的时候，响应式web的性能就明显高了。底层是Netty全双工，长连接
	public Mono<Object> get3(ServerHttpRequest request, String name, WebSession session){
		System.out.println(request.getHeaders());
		System.out.println("name: " + name); // 能得到name
		System.out.println("Name from query params: " + request.getQueryParams().get("name")); // 也能得到name
		// request.getBody();也会得到一个Flux，两边都能用Flux，数据序列。request中无session，没有session的概念
		/*
		name: xiaoming
		Name from query params: [xiaoming]
		 */

		/*
		J2EE里的Session不能用了，要用WebSession，用法有些许不同
		 */
		if (StringUtils.isEmpty(session.getAttribute("code"))){
			System.out.println("Session中没有，要set");
			session.getAttributes().put("code", 100);
		}
		System.out.println("Code = " + session.getAttribute("code"));
		return Mono.just("haha");
	}

	@GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public Flux<Person> sse(){
		// 1. 封装对象
		Flux<Person> flux = Flux.fromStream(IntStream.range(1, 10).mapToObj(i -> {
			try {
				Thread.sleep(new Random().nextInt(2000));
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return new Person(i, "xiaoming");
		}))
		.doOnSubscribe(sub -> {
			System.out.println("订阅");
		})
		.doOnComplete(() -> { // 需要一个Runnable
			System.out.println("Complete");
		})
		.doOnNext(data -> { // 有数据之后
			System.out.println("有数据了：" + data);
		});

		// 2. 对象连带里面的方法给了容器. 返回的每个Person对象，做到了在前端即时显示，有一个Person被new出来了，就在前端显示一个，
		//     客户端其实只发来了一次请求，全部Person对象返回之前，前端显示：Caution：请求还没结束。每个Person没重新包装成了个JSON（因为@RestController还在）
		// 这还不是长连接，因为他还是基于http的，什么是长连接？TCP就是长连接，http就不是。这里类似于文件下载, 类比前面的SSE，但是这里没有Servlet的东西
		return flux;
	}
}
