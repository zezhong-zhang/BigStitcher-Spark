/*-
 * #%L
 * Spark-based parallel BigStitcher project.
 * %%
 * Copyright (C) 2021 - 2024 Developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 2 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-2.0.html>.
 * #L%
 */
package net.preibisch.bigstitcher.spark.util;

import java.io.File;
import java.net.URI;

import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.hdf5.N5HDF5Writer;
import org.janelia.saalfeldlab.n5.universe.N5Factory.StorageFormat;

import net.preibisch.legacy.io.IOFunctions;
import util.URITools;

public class N5Util
{
	public static N5HDF5Writer sharedHDF5Writer = null;

	public static N5Writer createN5Writer(
			final URI n5PathURI,
			final StorageFormat storageType )
	{
		final N5Writer driverVolumeWriter;

		try
		{
			if ( storageType == StorageFormat.HDF5 )
			{
				if ( sharedHDF5Writer != null )
					return sharedHDF5Writer;

				final File dir = new File( URITools.fromURI( n5PathURI ) ).getParentFile();
				if ( !dir.exists() )
					dir.mkdirs();

				driverVolumeWriter = sharedHDF5Writer = new N5HDF5Writer( URITools.fromURI( n5PathURI ) );
			}
			else if ( storageType == StorageFormat.N5 || storageType == StorageFormat.ZARR )
			{
				driverVolumeWriter = URITools.instantiateN5Writer( storageType, n5PathURI );
			}
			else
				throw new RuntimeException( "storageType " + storageType + " not supported." );
		}
		catch ( Exception e )
		{
			IOFunctions.println( "Couldn't create/open " + storageType + " container '" + n5PathURI + "': " + e );
			return null;
		}

		return driverVolumeWriter;
	}

	/*
	// only supported for local spark HDF5 writes, needs to share a writer instance
	public static N5HDF5Writer hdf5DriverVolumeWriter = null;

	public static N5Writer createWriter(
			final String path,
			final StorageFormat storageType ) throws IOException // can be null if N5 or ZARR is written 
	{
		if ( StorageFormat.N5.equals(storageType) )
			return new N5FSWriter(path);
		else if ( StorageFormat.ZARR.equals(storageType) )
			return new N5ZarrWriter(path);
		else if ( StorageFormat.HDF5.equals(storageType) )
			return hdf5DriverVolumeWriter == null ? hdf5DriverVolumeWriter = new N5HDF5Writer( path ) : hdf5DriverVolumeWriter;
		else
			throw new RuntimeException( "storageType " + storageType + " not supported." );
	}
	*/
}
