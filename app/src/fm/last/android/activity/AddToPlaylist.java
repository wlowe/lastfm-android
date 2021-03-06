/***************************************************************************
 *   Copyright 2005-2009 Last.fm Ltd.                                      *
 *   Portions contributed by Casey Link, Lukasz Wisniewski,                *
 *   Mike Jennings, and Michael Novak Jr.                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.         *
 ***************************************************************************/
package fm.last.android.activity;

import java.io.IOException;
import java.util.ArrayList;

import android.app.Activity;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.View.OnClickListener;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;
import android.widget.AdapterView.OnItemClickListener;
import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.R;
import fm.last.android.adapter.ListAdapter;
import fm.last.android.adapter.ListEntry;
import fm.last.android.adapter.NotificationAdapter;
import fm.last.android.utils.ImageCache;
import fm.last.android.utils.UserTask;
import fm.last.api.LastFmServer;
import fm.last.api.RadioPlayList;
import fm.last.api.Session;
import fm.last.api.WSError;

/**
 * Activity adding a track to a user's Last.fm playlist
 * 
 * The track metadata is passed via intent extras INTENT_EXTRA_TRACK and
 * INTENT_EXTRA_ARTIST
 * 
 * @author Sam Steele <sam@last.fm>
 */
public class AddToPlaylist extends Activity {
	private ListView mPlaylistsList;
	private EditText mNewPlaylist;
	private Button mCreateBtn;
	private ImageCache mImageCache;
	LastFmServer mServer = AndroidLastFmServerFactory.getServer();

	public static final String INTENT_EXTRA_TRACK = "lastfm.track";
	public static final String INTENT_EXTRA_ARTIST = "lastfm.artist";

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.add_to_playlist);

		mNewPlaylist = (EditText) findViewById(R.id.new_playlist);
		mNewPlaylist.setOnKeyListener(new View.OnKeyListener() {

			public boolean onKey(View v, int keyCode, KeyEvent event) {
				switch (event.getKeyCode()) {
				case KeyEvent.KEYCODE_ENTER:
					mCreateBtn.performClick();
					return true;
				default:
					return false;
				}
			}

		});

		mCreateBtn = (Button) findViewById(R.id.create_button);
		mCreateBtn.setOnClickListener(new OnClickListener() {

			public void onClick(View v) {
				if (mNewPlaylist.getText().length() > 0) {
					new CreatePlaylistTask(mNewPlaylist.getText().toString()).execute((Void) null);
				}
			}

		});

		mPlaylistsList = (ListView) findViewById(R.id.playlists);
		mPlaylistsList.setOnItemClickListener(new OnItemClickListener() {

			public void onItemClick(AdapterView<?> l, View v, int position, long id) {
				ListAdapter adapter = (ListAdapter) mPlaylistsList.getAdapter();
				adapter.enableLoadBar(position);
				RadioPlayList playlist = (RadioPlayList) adapter.getItem(position);
				String artist = getIntent().getStringExtra(INTENT_EXTRA_ARTIST);
				String track = getIntent().getStringExtra(INTENT_EXTRA_TRACK);
				new AddToPlaylistTask(artist, track, playlist.getId()).execute((Void) null);
			}
		});

		new LoadPlaylistsTask().execute((Void) null);
	}

	@Override
	public void onResume() {
		super.onResume();
		try {
			LastFMApplication.getInstance().tracker.trackPageView("/AddToPlaylist");
		} catch (SQLiteException e) {
			//Google Analytics doesn't appear to be thread safe
		}
	}

	private ImageCache getImageCache() {
		if (mImageCache == null) {
			mImageCache = new ImageCache();
		}
		return mImageCache;
	}

	private class AddToPlaylistTask extends UserTask<Void, Void, Boolean> {
		String mArtist;
		String mTrack;
		String mPlaylistId;

		public AddToPlaylistTask(String artist, String track, String playlistId) {
			mArtist = artist;
			mTrack = track;
			mPlaylistId = playlistId;
		}

		@Override
		public Boolean doInBackground(Void... params) {
			Session session = LastFMApplication.getInstance().session;

			try {
				mServer.addTrackToPlaylist(mArtist, mTrack, mPlaylistId, session.getKey());
				return true;
			} catch (WSError e) {
				// 'invalidate parameters' error in this case means
				// "track already exists in playlist", which we will
				// usefully treat as a non-error.
				// of course we're assuming that we always get our
				// parameters right. but in the face of not enough
				// error codes from the api, what are you gonna do?
				if (e.getCode() == WSError.ERROR_InvalidParameters)
					return true;
			} catch (IOException e) {
				e.printStackTrace();
			}
			return false;
		}

		@Override
		public void onPostExecute(Boolean result) {
			((ListAdapter) mPlaylistsList.getAdapter()).disableLoadBar();
			if (result) {
				AddToPlaylist.this.finish();
			} else {
				Toast.makeText(AddToPlaylist.this, getString(R.string.addtoplaylist_adding_error), Toast.LENGTH_SHORT).show();
			}
		}
	}

	private class LoadPlaylistsTask extends UserTask<Void, Void, ArrayList<ListEntry>> {
		@Override
		public void onPreExecute() {
			mPlaylistsList.setAdapter(new NotificationAdapter(AddToPlaylist.this, NotificationAdapter.LOAD_MODE, getString(R.string.common_loading)));
		}

		@Override
		public ArrayList<ListEntry> doInBackground(Void... params) {
			Session session = LastFMApplication.getInstance().session;

			try {
				RadioPlayList[] playlists = mServer.getUserPlaylists(session.getName());
				if (playlists == null || playlists.length == 0)
					return null;

				ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
				for (int i = 0; i < playlists.length; i++) {
					ListEntry entry = new ListEntry(playlists[i], -1, playlists[i].getTitle());
					iconifiedEntries.add(entry);
				}
				return iconifiedEntries;
			} catch (IOException e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
			if (iconifiedEntries != null) {
				ListAdapter adaptor = new ListAdapter(AddToPlaylist.this, getImageCache());
				adaptor.setSourceIconified(iconifiedEntries);
				mPlaylistsList.setAdapter(adaptor);
			} else {
				mPlaylistsList.setAdapter(new NotificationAdapter(AddToPlaylist.this, NotificationAdapter.INFO_MODE,
						getString(R.string.addtoplaylist_noplaylists)));
			}
		}
	}

	private class CreatePlaylistTask extends UserTask<Void, Void, ArrayList<ListEntry>> {
		private String mTitle;

		public CreatePlaylistTask(String title) {
			mTitle = title;
		}

		@Override
		public void onPreExecute() {
			mNewPlaylist.setEnabled(false);
			Toast.makeText(AddToPlaylist.this, getString(R.string.addtoplaylist_creating), Toast.LENGTH_LONG).show();
		}

		@Override
		public ArrayList<ListEntry> doInBackground(Void... params) {
			Session session = LastFMApplication.getInstance().session;

			try {
				RadioPlayList[] playlists = mServer.createPlaylist(mTitle, "", session.getKey());
				if (playlists == null || playlists.length == 0)
					return null;

				playlists = mServer.getUserPlaylists(session.getName());
				if (playlists == null || playlists.length == 0)
					return null;

				ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
				for (int i = 0; i < playlists.length; i++) {
					ListEntry entry = new ListEntry(playlists[i], -1, playlists[i].getTitle());
					iconifiedEntries.add(entry);
				}

				return iconifiedEntries;
			} catch (WSError e) {
				// TODO: something useful...
			} catch (IOException e) {
				e.printStackTrace();
			}
			return null;
		}

		@Override
		public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
			if (iconifiedEntries != null) {
				mNewPlaylist.setText(""); // ?
				ListAdapter adapter = new ListAdapter(AddToPlaylist.this, getImageCache());
				adapter.setSourceIconified(iconifiedEntries);
				mPlaylistsList.setAdapter(adapter);
			} else {
				Toast.makeText(AddToPlaylist.this, getString(R.string.addtoplaylist_creating_error), Toast.LENGTH_SHORT).show();
			}
			mNewPlaylist.setEnabled(true);
		}
	}
}
