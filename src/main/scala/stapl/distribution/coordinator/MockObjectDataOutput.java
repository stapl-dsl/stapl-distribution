package stapl.distribution.coordinator;

import java.io.IOException;
import java.nio.ByteOrder;

import com.hazelcast.nio.ObjectDataOutput;

public class MockObjectDataOutput implements ObjectDataOutput {

	@Override
	public void write(int b) throws IOException {
		System.out.println("Int: " + b);
	}

	@Override
	public void write(byte[] b) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void write(byte[] b, int off, int len) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeBoolean(boolean v) throws IOException {
		System.out.println("Boolean: " + v);
	}

	@Override
	public void writeByte(int v) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeShort(int v) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeChar(int v) throws IOException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void writeInt(int v) throws IOException {
		System.out.println("Int: " + v);
	}

	@Override
	public void writeLong(long v) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeFloat(float v) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeDouble(double v) throws IOException {
		System.out.println("Double: " + v);
	}

	@Override
	public void writeBytes(String s) throws IOException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void writeChars(String s) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeUTF(String s) throws IOException {
		System.out.println("UTF: " + s);
	}

	@Override
	public void writeCharArray(char[] chars) throws IOException {
		throw new UnsupportedOperationException();

	}

	@Override
	public void writeIntArray(int[] ints) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeLongArray(long[] longs) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeDoubleArray(double[] values) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeFloatArray(float[] values) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeShortArray(short[] values) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public void writeObject(Object object) throws IOException {
		throw new UnsupportedOperationException();
	}

	@Override
	public byte[] toByteArray() {
		throw new UnsupportedOperationException();
	}

	@Override
	public ByteOrder getByteOrder() {
		throw new UnsupportedOperationException();
	}

}
