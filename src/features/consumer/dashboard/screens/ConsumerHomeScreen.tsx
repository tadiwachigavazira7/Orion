import { MaterialIcons } from '@expo/vector-icons';
import React, { useState } from 'react';
import { Image, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { theme } from '../../../../theme';
import { ZipCodeSelector } from '../components/ZipCodeSelector';

const logo = require('../../../../../assets/images/OrionLogo.png');

export default function ConsumerHomeScreen() {
  const [searchQuery, setSearchQuery] = useState('');

  const handleCameraPress = () => {
    console.log('Open hardware camera or file upload portal');
  };

  return (
    <View style={styles.container}>
      <View style={styles.topHeaderRow}>
        <ZipCodeSelector />
        <Image source={logo} style={styles.logoImage} resizeMode="contain" />
      </View>

      <View style={styles.searchSection}>
        <Text style={styles.sectionTitle}>Start shopping now</Text>
        
        <View style={styles.searchBarContainer}>
          <MaterialIcons name="search" size={20} color={theme.subtext} style={styles.searchIcon} />
          <TextInput
            style={styles.searchInput}
            value={searchQuery}
            onChangeText={setSearchQuery}
            placeholder="Search for an item"
            placeholderTextColor="#6B7280"
          />
          {searchQuery.length > 0 && (
            <Pressable onPress={() => setSearchQuery('')}>
              <Text style={styles.clearIcon}>✕</Text>
            </Pressable>
          )}
        </View>

        <Text style={styles.popularSearchesText}>
          Popular searches:{' '}
          <Text style={styles.italicText}>Pull poular searches from backened database</Text>
        </Text>
      </View>

      <View style={styles.uploadSection}>
        <Text style={styles.sectionTitle}>Or upload image:</Text>
        
        <Pressable 
          onPress={handleCameraPress}
          style={({ pressed }) => [
            styles.cameraCard,
            pressed && { opacity: 0.9 }
          ]}
        >
          <MaterialIcons name="photo-camera" size={44} color={theme.subtext} style={styles.cameraIcon} />
        </Pressable>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: theme.background,
    paddingHorizontal: 20,
    paddingTop: 10,
  },
  topHeaderRow: {
    width: '100%',
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 50,
  },
  logoImage: {
    width: 85,
    height: 85,
    marginTop: -20,
  },
  searchSection: {
    alignItems: 'center',
    width: '100%',
    marginBottom: 50,
  },
  sectionTitle: {
    fontSize: 24,
    fontWeight: '400',
    color: theme.text,
    fontFamily: 'serif',
    marginBottom: 14,
    textAlign: 'center',
  },
  searchBarContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '100%',
    height: 44,
    borderWidth: 2,
    borderColor: theme.border,
    borderRadius: 12,
    paddingHorizontal: 12,
    backgroundColor: theme.background,
  },
  searchIcon: {
    fontSize: 16,
    marginRight: 8,
  },
  searchInput: {
    flex: 1,
    height: '100%',
    fontSize: 16,
    color: theme.text,
  },
  clearIcon: {
    fontSize: 14,
    color: theme.subtext,
    paddingHorizontal: 4,
  },
  popularSearchesText: {
    fontSize: 12,
    color: theme.text,
    marginTop: 15,
    textAlign: 'center',
  },
  italicText: {
    fontStyle: 'italic',
  },
  uploadSection: {
    alignItems: 'center',
    width: '100%',
  },
  cameraCard: {
    width: '100%',
    height: 250,
    backgroundColor: theme.card,
    borderRadius: 28,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: 5,
    borderColor: theme.border,
  },
  cameraIcon: {
    fontSize: 44,
    color: theme.subtext,
  },
});