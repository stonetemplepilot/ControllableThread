package tests;

import java.util.LinkedList;
import java.util.Random;
import java.util.function.Consumer;

import com.stp.buildingblocks.ControllableThread;

public class SampleTest
{
    public static void main(String[] args)
    {
	LinkedList<Integer> queue1 = new LinkedList<>();
	Consumer<Integer> func1 = (i)->System.out.println(i);
	ControllableThread<Integer> t = new ControllableThread<>();
	Random r = new Random();

	t.start("test", queue1, func1);

	for (int i = 0; i < 100; ++i) {
	    t.step();
	    t.step();
	    t.step();

	    t.pause();
	    t.addWork(i);
	    t.resume();

	    for (int j = 0; j < 10; ++j) {
		t.addWork(j+1000);
	    }

	    t.step();
	    t.step();
	    t.step();

	    try {
		Thread.sleep(r.nextInt(3)+1);

		t.addWork(i+100);

		t.pause();

		Thread.sleep(r.nextInt(2));

		t.step();
	    } catch (Exception e) {
		e.printStackTrace();
	    }

	    t.resume();
	}

	for (int j = 0; j < 100; ++j) {
	    t.addWork(j+2000);
	    if ((j%25) == 0) t.waitForQuiesce();
	}

	t.pause();
	t.unPause();

	try {
	    Thread.sleep(r.nextInt(3)+1);
	} catch (Exception e) {
	    e.printStackTrace();
	}

	t.step();
	t.step();
	t.step();

	t.resume();

	t.waitForQuiesce();
	t.stop();
    }
}
