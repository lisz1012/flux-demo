package com.lisz.service;

import com.lisz.model.Person;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PersonService {
	private static Map<Integer, Person> map = new ConcurrentHashMap<>();

	static {
		for (int i = 0; i < 100; i++) {
			map.put(i, new Person(i, "xiaoming_" + i));
		}
	}

	public Person getPerson() {
		System.out.println("getPerson Thread: " + Thread.currentThread().getName());
		try {
			Thread.sleep(2000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return map.get(1);
	}

	public Person getPersonMax() {
		try {
			Thread.sleep(Long.MAX_VALUE);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		return map.get(1);
	}

	public Flux<Person> getPersons(){
		// 数据源也得是响应式的， Flux是观察者
		return Flux.fromIterable(map.values());
	}
}
